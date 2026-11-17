import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../src/lib/errors";
import { complete, testProvider } from "../src/ai/llm";
import { DEFAULT_PROVIDERS, getProviders, saveProviders, type ProviderConfig } from "../src/ai/providers";
import type { Env } from "../src/env";

const e = env as unknown as Env;
const KEYS = { INWORLD_API_KEY: "fake-inworld-key-1111", OPENAI_API_KEY: "fake-openai-key-2222", ANTHROPIC_API_KEY: "fake-anthropic-key-3333" };

function ok(text: string, model = "m") {
  return new Response(JSON.stringify({ model, choices: [{ message: { content: text } }] }), { status: 200 });
}
function mockFetch(fn: (url: string, init: RequestInit) => Promise<Response> | Response) {
  const f = vi.fn(async (u: any, i: any) => fn(String(u), i));
  vi.stubGlobal("fetch", f);
  return f;
}
const prov = (over: Partial<ProviderConfig> & { id: string }): ProviderConfig => ({
  label: over.id, kind: "openai_compat", baseUrl: `https://${over.id}.example.com/v1`, model: "mm",
  keyName: "OPENAI_API_KEY", enabled: true, ...over,
});

const saved: Record<string, unknown> = {};
beforeEach(async () => {
  for (const k of [...Object.keys(KEYS), "INWORLD_BASE_URL"]) saved[k] = (e as any)[k];
  await e.DB.prepare("DELETE FROM secrets").run();
  Object.assign(e, KEYS);
  await e.DB.prepare("UPDATE settings SET value='[]' WHERE key='ai_providers'").run();
});
afterEach(() => {
  vi.unstubAllGlobals();
  for (const k of Object.keys(saved)) {
    if (saved[k] === undefined) delete (e as any)[k];
    else (e as any)[k] = saved[k];
  }
});

const req = { system: "sys", user: "usr" };

describe("providers", () => {
  it("falls back to defaults when empty or invalid", async () => {
    expect((await getProviders(e)).map((p) => p.id)).toEqual(DEFAULT_PROVIDERS.map((p) => p.id));
    await e.DB.prepare("UPDATE settings SET value='not json' WHERE key='ai_providers'").run();
    expect((await getProviders(e)).length).toBe(4);
  });
  it("INWORLD_BASE_URL overrides inworld default", async () => {
    e.INWORLD_BASE_URL = "https://gw.example.com/v1";
    expect((await getProviders(e)).find((p) => p.id === "inworld")!.baseUrl).toBe("https://gw.example.com/v1");
  });
  it("INWORLD_BASE_URL that is not https is ignored", async () => {
    e.INWORLD_BASE_URL = "http://evil.example.com/v1";
    expect((await getProviders(e)).find((p) => p.id === "inworld")!.baseUrl).toBe("https://api.inworld.ai/v1");
    e.INWORLD_BASE_URL = "https://gw.example.com/v1/?x=1";
    expect((await getProviders(e)).find((p) => p.id === "inworld")!.baseUrl).toBe("https://api.inworld.ai/v1");
  });
  it("stored list with non-https baseUrl falls back to defaults; baseUrl normalized", async () => {
    const bad = [prov({ id: "x", baseUrl: "http://x.example.com/v1" })];
    await e.DB.prepare("UPDATE settings SET value=? WHERE key='ai_providers'").bind(JSON.stringify(bad)).run();
    expect((await getProviders(e)).map((p) => p.id)).toEqual(DEFAULT_PROVIDERS.map((p) => p.id));
    await saveProviders(e, [prov({ id: "n", baseUrl: "https://X.example.com/v1/" })]);
    expect((await getProviders(e))[0].baseUrl).toBe("https://x.example.com/v1");
  });
  it("saveProviders validates and round-trips", async () => {
    await saveProviders(e, [prov({ id: "b" }), prov({ id: "a" })]);
    expect((await getProviders(e)).map((p) => p.id)).toEqual(["b", "a"]);
    const bad = async (l: ProviderConfig[]) => {
      const err = await saveProviders(e, l).then(() => null, (x) => x);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(400);
    };
    await bad([prov({ id: "a" }), prov({ id: "a" })]);
    await bad([prov({ id: "a", kind: "nope" as any })]);
    await bad([prov({ id: "a", baseUrl: "http://x.example.com" })]);
    await bad([prov({ id: "a", baseUrl: "https://u:p@x.example.com/v1" })]);
    await bad([prov({ id: "a", baseUrl: "https://x.example.com/v1?k=1" })]);
    await bad([prov({ id: "a", baseUrl: "https://x.example.com/v1#f" })]);
    await bad([prov({ id: "a", baseUrl: "not a url" })]);
    await bad([prov({ id: "a", keyName: "SOME_OTHER" })]);
    await bad([prov({ id: "a", model: "" })]);
    await bad([prov({ id: "Upper" })]);
    await bad([prov({ id: "has space" })]);
    await bad([prov({ id: "x".repeat(33) })]);
    await saveProviders(e, [prov({ id: "ok_id-1" })]);
  });
  it("warns (without content) when ai_providers is invalid or INWORLD_BASE_URL is ignored", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    await getProviders(e); // '[]' placeholder: not a warning
    expect(warn).not.toHaveBeenCalled();
    await e.DB.prepare("UPDATE settings SET value='{\"secret\":1}' WHERE key='ai_providers'").run();
    await getProviders(e);
    await e.DB.prepare("UPDATE settings SET value='not json' WHERE key='ai_providers'").run();
    await getProviders(e);
    expect(warn).toHaveBeenCalledTimes(2);
    e.INWORLD_BASE_URL = "http://evil.example.com/v1";
    await getProviders(e);
    expect(warn).toHaveBeenCalledTimes(4); // invalid list + ignored base URL
    const logged = JSON.stringify(warn.mock.calls);
    expect(logged).not.toContain("evil");
    expect(logged).not.toContain("secret");
    warn.mockRestore();
  });
});

describe("complete", () => {
  it("respects order and returns first success", async () => {
    await saveProviders(e, [prov({ id: "p1", keyName: "INWORLD_API_KEY" }), prov({ id: "p2" })]);
    const f = mockFetch(() => ok("hello", "real-model"));
    const r = await complete(e, req);
    expect(r.text).toBe("hello");
    expect(r.providerId).toBe("p1");
    expect(r.model).toBe("real-model");
    expect(f).toHaveBeenCalledTimes(1);
    const [url, init] = f.mock.calls[0] as any;
    expect(url).toBe("https://p1.example.com/v1/chat/completions");
    expect(init.headers.Authorization).toBe(`Bearer ${KEYS.INWORLD_API_KEY}`);
    const body = JSON.parse(init.body);
    expect(body.temperature).toBe(0);
    expect(body.messages).toEqual([{ role: "system", content: "sys" }, { role: "user", content: "usr" }]);
  });
  it("falls back on 500 and on empty text", async () => {
    await saveProviders(e, [prov({ id: "p1" }), prov({ id: "p2" }), prov({ id: "p3" })]);
    let n = 0;
    mockFetch(() => (++n === 1 ? new Response("boom", { status: 500 }) : n === 2 ? ok("  ") : ok("third")));
    const r = await complete(e, req);
    expect(r.providerId).toBe("p3");
  });
  it("falls back on timeout", async () => {
    await saveProviders(e, [prov({ id: "p1", timeoutMs: 20 }), prov({ id: "p2" })]);
    mockFetch((url, init) =>
      url.includes("p1")
        ? new Promise<Response>((_, rej) => init.signal!.addEventListener("abort", () => rej(new Error("aborted"))))
        : ok("fine"),
    );
    expect((await complete(e, req)).providerId).toBe("p2");
  });
  it("all failing -> 502 ai_unavailable with no secrets in message", async () => {
    await saveProviders(e, [prov({ id: "p1" }), prov({ id: "p2" })]);
    mockFetch(() => new Response(`echo Bearer ${KEYS.OPENAI_API_KEY} body`, { status: 401 }));
    const err = await complete(e, req).then(() => null, (x) => x);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(502);
    expect(err.code).toBe("ai_unavailable");
    expect(JSON.stringify([err.message, err.stack])).not.toContain("fake-");
  });
  it("skips disabled providers and providers without a key", async () => {
    delete (e as any).INWORLD_API_KEY;
    await saveProviders(e, [
      prov({ id: "off", enabled: false }),
      prov({ id: "nokey", keyName: "INWORLD_API_KEY" }),
      prov({ id: "good" }),
    ]);
    const f = mockFetch(() => ok("x"));
    expect((await complete(e, req)).providerId).toBe("good");
    expect(f).toHaveBeenCalledTimes(1);
  });
  it("no usable providers -> 502", async () => {
    const f = mockFetch(() => ok("x"));
    delete (e as any).INWORLD_API_KEY; delete (e as any).OPENAI_API_KEY;
    await expect(complete(e, req)).rejects.toMatchObject({ status: 502, code: "ai_unavailable" });
    expect(f).not.toHaveBeenCalled();
  });
  it("json mode sets response_format; retries without on 400 mentioning it", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    const bodies: any[] = [];
    mockFetch((_u, init) => {
      const b = JSON.parse(init.body as string);
      bodies.push(b);
      return b.response_format ? new Response("unsupported response_format", { status: 400 }) : ok("{}");
    });
    const r = await complete(e, { ...req, json: true, maxTokens: 77 });
    expect(r.text).toBe("{}");
    expect(bodies).toHaveLength(2);
    expect(bodies[0].response_format).toEqual({ type: "json_object" });
    expect(bodies[1].response_format).toBeUndefined();
    expect(bodies[1].max_tokens).toBe(77);
  });
  it("anthropic request shape", async () => {
    await saveProviders(e, [prov({ id: "an", kind: "anthropic", baseUrl: "https://api.anthropic.com/v1", model: "claude-x", keyName: "ANTHROPIC_API_KEY" })]);
    const f = mockFetch(() => new Response(JSON.stringify({ model: "claude-x", content: [{ type: "text", text: "hi" }] }), { status: 200 }));
    const r = await complete(e, { ...req, maxTokens: 50 });
    expect(r.text).toBe("hi");
    const [url, init] = f.mock.calls[0] as any;
    expect(url).toBe("https://api.anthropic.com/v1/messages");
    expect(init.headers["x-api-key"]).toBe(KEYS.ANTHROPIC_API_KEY);
    expect(init.headers["anthropic-version"]).toBe("2023-06-01");
    expect(init.headers.Authorization).toBeUndefined();
    expect(JSON.parse(init.body)).toEqual({ model: "claude-x", max_tokens: 50, system: "sys", messages: [{ role: "user", content: "usr" }] });
  });
  it("finishReason: anthropic max_tokens -> length", async () => {
    await saveProviders(e, [prov({ id: "an", kind: "anthropic", keyName: "ANTHROPIC_API_KEY" })]);
    mockFetch(() => new Response(JSON.stringify({ content: [{ type: "text", text: "hi" }], stop_reason: "max_tokens" }), { status: 200 }));
    expect((await complete(e, req)).finishReason).toBe("length");
    mockFetch(() => new Response(JSON.stringify({ content: [{ type: "text", text: "hi" }], stop_reason: "end_turn" }), { status: 200 }));
    expect((await complete(e, req)).finishReason).toBe("stop");
  });
  it("finishReason: openai length -> length, stop -> stop", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    mockFetch(() => new Response(JSON.stringify({ choices: [{ message: { content: "x" }, finish_reason: "length" }] }), { status: 200 }));
    expect((await complete(e, req)).finishReason).toBe("length");
    mockFetch(() => new Response(JSON.stringify({ choices: [{ message: { content: "x" }, finish_reason: "stop" }] }), { status: 200 }));
    expect((await complete(e, req)).finishReason).toBe("stop");
  });
});

describe("hardening", () => {
  it("oversized streamed body falls through to next provider", async () => {
    await saveProviders(e, [prov({ id: "p1" }), prov({ id: "p2" })]);
    mockFetch((url) => {
      if (!url.includes("p1")) return ok("fine");
      const chunk = new Uint8Array(256 * 1024).fill(97);
      let n = 0;
      return new Response(new ReadableStream({ pull(c) { n++ < 8 ? c.enqueue(chunk) : c.close(); } }), { status: 200 });
    });
    expect((await complete(e, req)).providerId).toBe("p2");
  });
  it("declared oversized content-length is rejected", async () => {
    await saveProviders(e, [prov({ id: "p1" }), prov({ id: "p2" })]);
    mockFetch((url) => url.includes("p1")
      ? new Response("{}", { status: 200, headers: { "content-length": "5000000" } })
      : ok("fine"));
    expect((await complete(e, req)).providerId).toBe("p2");
  });
  it("logs only provider id and reason", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    const log = vi.spyOn(console, "log").mockImplementation(() => {});
    mockFetch(() => new Response(`Bearer ${KEYS.OPENAI_API_KEY}`, { status: 401 }));
    await complete(e, req).catch(() => {});
    expect(log.mock.calls.map((c) => c.join(" "))).toEqual(["ai provider p1 failed: http_401"]);
    log.mockRestore();
  });
  it("anthropic empty content falls through", async () => {
    await saveProviders(e, [prov({ id: "an", kind: "anthropic", keyName: "ANTHROPIC_API_KEY" }), prov({ id: "p2" })]);
    mockFetch((url) => url.includes("/an.") ? new Response(JSON.stringify({ content: [] }), { status: 200 }) : ok("fine"));
    expect((await complete(e, req)).providerId).toBe("p2");
  });
  it("400 without response_format is not retried", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    const f = mockFetch(() => new Response("bad request", { status: 400 }));
    await expect(complete(e, { ...req, json: true })).rejects.toMatchObject({ code: "ai_unavailable" });
    expect(f).toHaveBeenCalledTimes(1);
  });
});

describe("testProvider", () => {
  it("reports no_key and not_configured", async () => {
    await saveProviders(e, [prov({ id: "p1", keyName: "CUSTOM_AI_KEY_1" })]);
    expect((await testProvider(e, "p1")).error).toBe("no_key");
    await saveProviders(e, [prov({ id: "p2", enabled: false, model: "", baseUrl: "" })]);
    expect((await testProvider(e, "p2")).error).toBe("not_configured");
  });
  it("reports ok, ms and model", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    const f = mockFetch(() => ok("ok", "routed/model"));
    const r = await testProvider(e, "p1");
    expect(r.ok).toBe(true);
    expect(r.model).toBe("routed/model");
    expect(typeof r.ms).toBe("number");
    const b = JSON.parse((f.mock.calls[0] as any)[1].body);
    expect(b.max_tokens).toBe(5);
    expect(b.messages[b.messages.length - 1].content).toBe("Reply with: ok");
  });
  it("reports sanitized failure", async () => {
    await saveProviders(e, [prov({ id: "p1" })]);
    mockFetch(() => new Response(`Bearer ${KEYS.OPENAI_API_KEY}`, { status: 401 }));
    const r = await testProvider(e, "p1");
    expect(r.ok).toBe(false);
    expect(JSON.stringify(r)).not.toContain("fake-");
    expect((await testProvider(e, "nope")).ok).toBe(false);
  });
});
