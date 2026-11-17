import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env } from "../src/env";

vi.mock("../src/ai/llm", async (orig) => ({
  ...(await orig<typeof import("../src/ai/llm")>()),
  testProvider: vi.fn(),
  complete: vi.fn(),
}));
import { complete, testProvider } from "../src/ai/llm";
import worker from "../src/index";
import { getProviders } from "../src/ai/providers";
import { getSecret, secretStatus, setSecret } from "../src/lib/secrets";
import { randomToken, sha256Hex } from "../src/lib/crypto";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

const tester = vi.mocked(testProvider);
const llm = vi.mocked(complete);
const e = env as unknown as Env;

let sent: unknown[];
const fakeQueue = {
  send: async (body: unknown) => { sent.push(body); },
  sendBatch: async (msgs: Iterable<{ body: unknown }>) => { for (const m of msgs) sent.push(m.body); },
};
let envOver: Partial<Env>;
const testEnv = () => ({ ...e, IMPORT_QUEUE: fakeQueue, ...envOver }) as unknown as Env;
const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;

type Session = { cookie: string; csrf: string; userId: string };

async function ownerSession(): Promise<Session> {
  const u = await makeUser("owner", "Owner");
  const id = randomToken(32);
  const csrf = randomToken(32);
  await e.DB.prepare("INSERT INTO sessions (id_hash, user_id, csrf, expires_at) VALUES (?, ?, ?, ?)")
    .bind(await sha256Hex(id), u.id, csrf, Date.now() + 3_600_000)
    .run();
  return { cookie: `ot_session=${id}`, csrf, userId: u.id };
}

function fetchW(path: string, init: RequestInit = {}) {
  return worker.fetch!(new Request(ORIGIN + path, { redirect: "manual", ...init }) as any, testEnv(), ctx);
}
const get = (s: Session | null, path: string) => fetchW(path, { headers: s ? { cookie: s.cookie } : {} });
function postForm(s: Session, path: string, fields: [string, string][] | Record<string, string>) {
  const body = new URLSearchParams(Array.isArray(fields) ? fields : Object.entries(fields));
  body.set("_csrf", s.csrf);
  return fetchW(path, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: s.cookie },
    body,
  });
}
function postJson(s: Session, path: string, body?: unknown) {
  return fetchW(path, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json", origin: ORIGIN, cookie: s.cookie, "x-csrf": s.csrf },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** The provider form as the page renders it: every row's fields in their current order. */
async function providerFields(extra: [string, string][] = []): Promise<[string, string][]> {
  const out: [string, string][] = [];
  for (const p of await getProviders(e)) {
    out.push(["order", p.id], [`model_${p.id}`, p.model], [`base_${p.id}`, p.baseUrl]);
    if (p.enabled) out.push([`enabled_${p.id}`, "on"]);
  }
  return [...out, ...extra];
}

let s: Session;
beforeEach(async () => {
  tester.mockReset();
  llm.mockReset();
  sent = [];
  envOver = {};
  await clearSongs();
  await e.DB.batch([
    e.DB.prepare("DELETE FROM sessions"), e.DB.prepare("DELETE FROM devices"), e.DB.prepare("DELETE FROM users"),
    e.DB.prepare("DELETE FROM secrets"), e.DB.prepare("DELETE FROM ai_usage"),
    e.DB.prepare("DELETE FROM import_jobs"), e.DB.prepare("DELETE FROM import_job_items"),
    e.DB.prepare("UPDATE settings SET value='[]' WHERE key='ai_providers'"),
    e.DB.prepare("UPDATE settings SET value='100' WHERE key='ai_daily_limit'"),
    e.DB.prepare("UPDATE settings SET value='4000' WHERE key='ai_max_output_tokens'"),
  ]);
  s = await ownerSession();
});

describe("/portal/ai access", () => {
  it("requires the owner", async () => {
    const r = await get(null, "/portal/ai");
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/login");
    const member = await makeUser("user", "Mia");
    const r2 = await fetchW("/portal/ai", { headers: member.headers });
    expect(r2.status).toBe(302);
    // The test endpoint is behind the CSRF guard too.
    const r3 = await fetchW("/portal/ai/test/openai", { method: "POST", headers: { origin: ORIGIN, cookie: s.cookie, accept: "application/json" } });
    expect(r3.status).toBe(403);
  });

  it("is in the nav and lists gateways, keys, limits and Apify", async () => {
    const r = await get(s, "/portal/ai");
    expect(r.status).toBe(200);
    expect(r.headers.get("content-security-policy")).toContain("script-src 'self'");
    const html = await r.text();
    expect(html).toContain('href="/portal/ai" aria-current="page"');
    expect(html).toContain("AI &amp; keys");
    for (const label of ["Inworld Router", "OpenAI", "Anthropic", "Custom gateway"]) expect(html).toContain(label);
    expect(html).toContain('value="anthropic/claude-haiku-5-5"'); // datalist suggestion
    expect(html).toContain('name="ai_daily_limit"');
    expect(html).toContain('name="ai_max_output_tokens"');
    expect(html).toContain('name="apify_actor"');
    expect(html).toContain("From deploy");
    expect(html).toContain("Missing");
    expect(html).not.toContain("data-secrets-warning");
  });
});

describe("keys", () => {
  it("stores a key encrypted and only ever shows last4", async () => {
    const key = "sk-live-SUPERSECRETVALUE-9876";
    const r = await postForm(s, "/portal/ai/keys", { name: "ANTHROPIC_API_KEY", value: `  ${key}  `, intent: "save" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toMatch(/^\/portal\/ai/);
    const row = await e.DB.prepare("SELECT ciphertext, iv, last4 FROM secrets WHERE name='ANTHROPIC_API_KEY'").first<any>();
    expect(JSON.stringify(row)).not.toContain("SUPERSECRET");
    expect(row.last4).toBe("9876");
    expect(await getSecret(e, "ANTHROPIC_API_KEY")).toBe(key); // trimmed on save
    const html = await (await get(s, "/portal/ai")).text();
    expect(html).toContain("••••9876");
    expect(html).not.toContain("SUPERSECRET");
    expect(html).not.toContain(key);
    // env keys show last4 only
    expect(html).toContain("••••1234");
    expect(html).not.toContain("test-inworld-key");
    expect(html).not.toContain("test-openai-key");
    expect(html).not.toContain("test-apify-token");
  });

  it("removes a portal key", async () => {
    await setSecret(e, "OPENAI_API_KEY", "portal-openai-key-abcd");
    const r = await postForm(s, "/portal/ai/keys", { name: "OPENAI_API_KEY", intent: "remove" });
    expect(r.status).toBe(302);
    expect((await secretStatus(e)).find((x) => x.name === "OPENAI_API_KEY")!.source).toBe("env");
  });

  it("rejects a short key and an unknown name without echoing the value", async () => {
    const r = await postForm(s, "/portal/ai/keys", { name: "OPENAI_API_KEY", value: "short", intent: "save" });
    expect(r.status).toBe(400);
    expect(await r.text()).not.toContain('value="short"');
    const r2 = await postForm(s, "/portal/ai/keys", { name: "NOPE", value: "long-enough-value", intent: "save" });
    expect(r2.status).toBe(400);
  });

  it("warns and disables saving when SECRETS_KEY is missing", async () => {
    envOver = { SECRETS_KEY: undefined };
    const html = await (await get(s, "/portal/ai")).text();
    expect(html).toContain("data-secrets-warning");
    const r = await postForm(s, "/portal/ai/keys", { name: "OPENAI_API_KEY", value: "some-long-key-value", intent: "save" });
    expect(r.status).toBe(503);
  });
});

describe("gateways", () => {
  it("reorder persists", async () => {
    const r = await postForm(s, "/portal/ai/providers", await providerFields([["move", "openai:up"]]));
    expect(r.status).toBe(302);
    expect((await getProviders(e)).map((p) => p.id)).toEqual(["openai", "inworld", "anthropic", "custom1"]);
    const r2 = await postForm(s, "/portal/ai/providers", await providerFields([["move", "custom1:down"]]));
    expect(r2.status).toBe(302);
    expect((await getProviders(e)).map((p) => p.id)).toEqual(["openai", "inworld", "anthropic", "custom1"]);
    const html = await (await get(s, "/portal/ai")).text();
    expect(html.indexOf("OpenAI")).toBeLessThan(html.indexOf("Inworld Router"));
  });

  it("saves model, enabled and custom base URL; rejects http", async () => {
    let fields = await providerFields();
    fields = fields.map(([k, v]) => [k, k === "model_custom1" ? "llama-3.3" : k === "base_custom1" ? "https://gw.example.com/v1/" : v]);
    fields.push(["enabled_custom1", "on"], ["enabled_anthropic", "on"]);
    const r = await postForm(s, "/portal/ai/providers", fields);
    expect(r.status).toBe(302);
    const ps = await getProviders(e);
    const c1 = ps.find((p) => p.id === "custom1")!;
    expect(c1).toMatchObject({ enabled: true, model: "llama-3.3", baseUrl: "https://gw.example.com/v1" });
    expect(ps.find((p) => p.id === "anthropic")!.enabled).toBe(true);
    // Built-in gateways keep their base URL whatever the form says.
    const bad = (await providerFields()).map(([k, v]): [string, string] => [k, k === "base_custom1" ? "http://gw.example.com" : k === "base_openai" ? "https://evil.example.com" : v]);
    const r2 = await postForm(s, "/portal/ai/providers", bad);
    expect(r2.status).toBe(400);
    expect((await getProviders(e)).find((p) => p.id === "custom1")!.baseUrl).toBe("https://gw.example.com/v1");
    const ok = (await providerFields()).map(([k, v]): [string, string] => [k, k === "base_openai" ? "https://evil.example.com" : v]);
    expect((await postForm(s, "/portal/ai/providers", ok)).status).toBe(302);
    expect((await getProviders(e)).find((p) => p.id === "openai")!.baseUrl).toBe("https://api.openai.com/v1");
  });

  it("Test calls the provider and returns latency + model as JSON", async () => {
    tester.mockResolvedValueOnce({ ok: true, ms: 412, model: "gpt-4.1-mini-2025" });
    const r = await postJson(s, "/portal/ai/test/openai");
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ ok: true, ms: 412, model: "gpt-4.1-mini-2025" });
    expect(tester.mock.calls[0][1]).toBe("openai");
    tester.mockResolvedValueOnce({ ok: false, ms: 0, error: "no_key" });
    expect(await (await postJson(s, "/portal/ai/test/custom1")).json()).toEqual({ ok: false, ms: 0, error: "no_key" });
    const bad = await postJson(s, "/portal/ai/test/BAD!");
    expect(bad.status).toBe(404);
    expect(await bad.json()).toEqual({ error: "not_found" });
  });
});

describe("limits and Apify", () => {
  it("saves limits and shows today's usage", async () => {
    const day = new Date().toISOString().slice(0, 10);
    await e.DB.prepare("INSERT INTO ai_usage (day, count) VALUES (?, 7), ('2000-01-01', 55)").bind(day).run();
    const r = await postForm(s, "/portal/ai/limits", { ai_daily_limit: "250", ai_max_output_tokens: "6000" });
    expect(r.status).toBe(302);
    const html = await (await get(s, "/portal/ai")).text();
    expect(html).toContain('value="250"');
    expect(html).toContain('value="6000"');
    expect(html).toMatch(/data-usage="7"/);
    const bad = await postForm(s, "/portal/ai/limits", { ai_daily_limit: "-1", ai_max_output_tokens: "6000" });
    expect(bad.status).toBe(400);
  });

  it("saves the Apify actor and validates it", async () => {
    expect((await postForm(s, "/portal/ai/apify", { apify_actor: "someone~my-actor" })).status).toBe(302);
    const row = await e.DB.prepare("SELECT value FROM settings WHERE key='apify_actor'").first<{ value: string }>();
    expect(row!.value).toBe("someone~my-actor");
    expect((await postForm(s, "/portal/ai/apify", { apify_actor: "https://x" })).status).toBe(400);
  });
});

describe("link import", () => {
  it("transfer page has the links form and AI select", async () => {
    const html = await (await get(s, "/portal/transfer")).text();
    expect(html).toContain("data-links");
    expect(html).toContain('name="urls"');
    expect(html).toMatch(/<select[^>]*name="ai"/);
  });

  it("the links form creates a job and the job page and JSON show progress", async () => {
    const r = await postForm(s, "/portal/transfer/links", { urls: "https://example.com/a\n\nhttps://example.com/b\nhttp://127.0.0.1/x" });
    expect(r.status).toBe(302);
    const loc = r.headers.get("location")!;
    const id = loc.match(/^\/portal\/transfer\/jobs\/([\w]+)$/)![1];
    expect(sent.length).toBe(2);
    const page = await get(s, loc);
    expect(page.status).toBe(200);
    const html = await page.text();
    expect(html).toContain("https://example.com/a");
    expect(html).toContain(`data-job="${id}"`);
    // Mark one item done with a song: JSON (session + X-CSRF) and the page link to the editor.
    await e.DB.prepare("UPDATE import_job_items SET status='done', song_id='S1' WHERE job_id=? AND idx=0").bind(id).run();
    const j = await fetchW(`/v1/import/jobs/${id}`, { headers: { cookie: s.cookie, "x-csrf": s.csrf } });
    expect(j.status).toBe(200);
    const job = (await j.json()) as any;
    expect(job.items[0]).toMatchObject({ status: "done", songId: "S1" });
    expect(await (await get(s, loc)).text()).toContain('href="/portal/songs/S1"');
  });

  it("the JSON endpoint creates jobs for the owner session", async () => {
    const r = await postJson(s, "/v1/import/links", { urls: ["https://example.com/c"] });
    expect(r.status).toBe(202);
    expect(((await r.json()) as any).jobId).toBeTruthy();
  });

  it("accepts a links form of 200 long URLs (above the 64 KB plain-form cap)", async () => {
    const urls = Array.from({ length: 200 }, (_, i) => `https://example.com/${String(i).padStart(3, "0")}/${"a".repeat(1900)}`);
    const r = await postForm(s, "/portal/transfer/links", { urls: urls.join("\n") });
    expect(r.status).toBe(302);
    expect(sent.length).toBe(200);
  });

  it("an empty links form is a 400 page", async () => {
    const r = await postForm(s, "/portal/transfer/links", { urls: "   " });
    expect(r.status).toBe(400);
  });
});

describe("editor", () => {
  it("has the Convert with AI button", async () => {
    const html = await (await get(s, "/portal/songs/new")).text();
    expect(html).toContain("data-ai-convert");
    expect(html).toContain("Convert with AI");
  });

  it("/v1/convert with ai:on reports confidence and provider", async () => {
    llm.mockResolvedValueOnce({
      text: JSON.stringify({ chordpro: "{title: X}\n[G]la la\n", language: "en", categories: [], key: "G", confidence: 0.88 }),
      providerId: "inworld", model: "auto", ms: 5,
    });
    const r = await postJson(s, "/v1/convert", { text: "G\nla la\n", ai: "on" });
    expect(r.status).toBe(200);
    const j = (await r.json()) as any;
    expect(j).toMatchObject({ via: "ai", confidence: 0.88, provider: "inworld" });
  });
});
