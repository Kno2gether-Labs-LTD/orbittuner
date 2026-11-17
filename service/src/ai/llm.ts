import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { getSecret } from "../lib/secrets";
import { getProviders, type ProviderConfig } from "./providers";

export type CompleteRequest = { system: string; user: string; maxTokens?: number; json?: boolean };
/** finishReason is normalised: "length" when the output hit maxTokens, "stop" on a normal end, else the raw value. */
export type CompleteResult = { text: string; providerId: string; model: string; ms: number; finishReason?: string };

function normFinish(v: unknown, lengthValue: string, stopValues: string[]): string | undefined {
  if (typeof v !== "string") return undefined;
  if (v === lengthValue) return "length";
  if (stopValues.includes(v)) return "stop";
  return v.slice(0, 32);
}

const DEFAULT_TIMEOUT_MS = 45_000;
const MAX_TIMEOUT_MS = 120_000;
const DEFAULT_MAX_TOKENS = 4000;
const MAX_RESPONSE_BYTES = 1_048_576;

// Internal failure carrying only a non-sensitive reason; never a provider body.
class ProviderFailure extends Error {
  constructor(public readonly reason: string) {
    super(reason);
  }
}

async function readCapped(res: Response): Promise<string> {
  if (!res.body) return "";
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_RESPONSE_BYTES) {
      await reader.cancel().catch(() => {});
      throw new ProviderFailure("too_large");
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    all.set(c, off);
    off += c.byteLength;
  }
  return new TextDecoder().decode(all);
}

async function post(url: string, headers: Record<string, string>, body: unknown, timeoutMs: number) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", ...headers },
      body: JSON.stringify(body),
      signal: ctl.signal,
    });
    const declared = Number(res.headers.get("content-length"));
    if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) {
      await res.body?.cancel().catch(() => {});
      throw new ProviderFailure("too_large");
    }
    const text = await readCapped(res);
    return { status: res.status, ok: res.ok, text };
  } catch (err) {
    if (err instanceof ProviderFailure) throw err;
    throw new ProviderFailure("network_or_timeout");
  } finally {
    clearTimeout(t);
  }
}

function parseJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    throw new ProviderFailure("bad_response");
  }
}

async function callProvider(p: ProviderConfig, key: string, req: CompleteRequest): Promise<{ text: string; model: string; finishReason?: string }> {
  const timeout = Math.min(p.timeoutMs ?? DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);
  const maxTokens = req.maxTokens ?? DEFAULT_MAX_TOKENS;
  const base = p.baseUrl.replace(/\/+$/, "");

  if (p.kind === "anthropic") {
    const r = await post(
      `${base}/messages`,
      { "x-api-key": key, "anthropic-version": "2023-06-01" },
      { model: p.model, max_tokens: maxTokens, system: req.system, messages: [{ role: "user", content: req.user }] },
      timeout,
    );
    if (!r.ok) throw new ProviderFailure(`http_${r.status}`);
    const j = parseJson(r.text);
    const text = Array.isArray(j?.content)
      ? j.content.filter((b: any) => b?.type === "text" && typeof b.text === "string").map((b: any) => b.text).join("")
      : "";
    if (!text.trim()) throw new ProviderFailure("empty");
    return {
      text,
      model: typeof j.model === "string" ? j.model : p.model,
      finishReason: normFinish(j.stop_reason, "max_tokens", ["end_turn", "stop_sequence"]),
    };
  }

  const body: Record<string, unknown> = {
    model: p.model,
    messages: [
      { role: "system", content: req.system },
      { role: "user", content: req.user },
    ],
    max_tokens: maxTokens,
    temperature: 0,
  };
  if (req.json) body.response_format = { type: "json_object" };
  const headers = { Authorization: `Bearer ${key}` };
  let r = await post(`${base}/chat/completions`, headers, body, timeout);
  if (r.status === 400 && req.json && /response_format/i.test(r.text)) {
    delete body.response_format;
    r = await post(`${base}/chat/completions`, headers, body, timeout);
  }
  if (!r.ok) throw new ProviderFailure(`http_${r.status}`);
  const j = parseJson(r.text);
  const content = j?.choices?.[0]?.message?.content;
  const text = typeof content === "string" ? content : "";
  if (!text.trim()) throw new ProviderFailure("empty");
  return {
    text,
    model: typeof j.model === "string" ? j.model : p.model,
    finishReason: normFinish(j?.choices?.[0]?.finish_reason, "length", ["stop"]),
  };
}

export async function complete(env: Env, req: CompleteRequest): Promise<CompleteResult> {
  for (const p of await getProviders(env)) {
    if (!p.enabled || !p.baseUrl || !p.model) continue;
    const key = await getSecret(env, p.keyName);
    if (!key) continue;
    const start = Date.now();
    try {
      const r = await callProvider(p, key, req);
      return { text: r.text, providerId: p.id, model: r.model, ms: Date.now() - start, finishReason: r.finishReason };
    } catch (err) {
      // Only the reason code is logged; bodies/keys are deliberately dropped.
      console.log(`ai provider ${p.id} failed: ${err instanceof ProviderFailure ? err.reason : "error"}`);
    }
  }
  throw new ApiError(502, "ai_unavailable");
}

export async function testProvider(
  env: Env,
  id: string,
): Promise<{ ok: boolean; ms: number; model?: string; error?: string }> {
  const p = (await getProviders(env)).find((x) => x.id === id);
  if (!p) return { ok: false, ms: 0, error: "unknown_provider" };
  if (!p.baseUrl || !p.model) return { ok: false, ms: 0, error: "not_configured" };
  const key = await getSecret(env, p.keyName);
  if (!key) return { ok: false, ms: 0, error: "no_key" };
  const start = Date.now();
  try {
    const r = await callProvider(p, key, { system: "You are a connectivity check.", user: "Reply with: ok", maxTokens: 5 });
    return { ok: true, ms: Date.now() - start, model: r.model };
  } catch (err) {
    return { ok: false, ms: Date.now() - start, error: err instanceof ProviderFailure ? err.reason : "failed" };
  }
}
