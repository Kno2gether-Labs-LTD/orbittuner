import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env } from "../src/env";
import { ApiError } from "../src/lib/errors";

vi.mock("../src/ai/llm", async (orig) => ({ ...(await orig<typeof import("../src/ai/llm")>()), complete: vi.fn() }));
vi.mock("../src/ai/fetchPage", async (orig) => ({
  ...(await orig<typeof import("../src/ai/fetchPage")>()),
  fetchPage: vi.fn(),
}));
import { complete } from "../src/ai/llm";
import { fetchPage } from "../src/ai/fetchPage";
import worker from "../src/index";
import type { ImportMessage } from "../src/ai/jobs";
import { AI_REQUESTS_PER_MINUTE, planLimit } from "../src/auth/plans";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

const llm = vi.mocked(complete);
const page = vi.mocked(fetchPage);

type Sent = { body: ImportMessage };
let sent: Sent[];
const fakeQueue = { sendBatch: async (msgs: Iterable<Sent>) => { for (const m of msgs) sent.push(m); } };
const testEnv = () => ({ ...(env as unknown as Env), IMPORT_QUEUE: fakeQueue }) as unknown as Env;
const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;

const cho = (title: string) => `{title: ${title}}\n{artist: Trad}\n{key: G}\n\n[G]Amazing [C]grace how [G]sweet\n`;
const aiReply = (title: string, confidence = 0.9) => ({
  text: JSON.stringify({ chordpro: cho(title), language: "en", categories: [], key: "G", confidence }),
  providerId: "p1", model: "m1", ms: 1,
});
const CHORDED = "G        C          G\nAmazing grace how sweet\n";
const pageOk = () => ({ text: CHORDED, via: "direct" as const, finalUrl: "https://example.com/" });

type Who = { id: string; headers: Record<string, string> };
function call(who: Who, path: string, body: unknown) {
  return worker.fetch!(new Request(ORIGIN + path, {
    method: "POST", headers: { ...who.headers, "content-type": "application/json" }, body: JSON.stringify(body),
  }) as any, testEnv(), ctx);
}
function get(who: Who, path: string) {
  return worker.fetch!(new Request(ORIGIN + path, { headers: who.headers }) as any, testEnv(), ctx);
}
function upload(who: Who, files: Record<string, string>, ai = "on") {
  const fd = new FormData();
  fd.set("ai", ai);
  for (const [name, text] of Object.entries(files)) fd.append("file", new File([text], name));
  return worker.fetch!(new Request(ORIGIN + "/v1/import", { method: "POST", headers: who.headers, body: fd }) as any, testEnv(), ctx);
}
async function runQueue(bodies: ImportMessage[]) {
  const messages = bodies.map((body, i) => ({ id: `m${i}`, timestamp: new Date(), body, attempts: 1, ack: vi.fn(), retry: vi.fn() }));
  await worker.queue({ queue: "q", messages, ackAll: vi.fn(), retryAll: vi.fn() } as unknown as MessageBatch<ImportMessage>, testEnv());
}

const used = async (who: Who) =>
  (await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(who.id).first<{ count: number }>())?.count ?? 0;
const today = () => new Date().toISOString().slice(0, 10);
const setUsed = (who: Who, n: number) =>
  env.DB.prepare("INSERT OR REPLACE INTO user_ai_usage (user_id, day, count) VALUES (?, ?, ?)").bind(who.id, today(), n).run();

/** A Google-linked user (Free unless a plan is given). */
async function google(plan: "free" | "plus" = "free", expires: number | null = null) {
  const u = await makeUser("user", "g");
  await env.DB.prepare("UPDATE users SET google_sub = ?, email = 'g@example.com', plan = ?, plan_expires_at = ? WHERE id = ?")
    .bind(`sub-${u.id}`, plan, expires, u.id).run();
  return u;
}

beforeEach(async () => {
  llm.mockReset();
  page.mockReset();
  sent = [];
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM ai_usage"), env.DB.prepare("DELETE FROM user_ai_usage"),
    env.DB.prepare("DELETE FROM ai_request_attempts"),
    env.DB.prepare("DELETE FROM import_jobs"), env.DB.prepare("DELETE FROM import_job_items"),
    env.DB.prepare("UPDATE settings SET value='100' WHERE key='ai_daily_limit'"),
    env.DB.prepare("UPDATE settings SET value='1' WHERE key='anon_ai_daily'"),
    env.DB.prepare("UPDATE settings SET value='3' WHERE key='free_ai_daily'"),
    env.DB.prepare("UPDATE settings SET value='50' WHERE key='plus_ai_daily'"),
  ]);
  let n = 0;
  llm.mockImplementation(async () => aiReply(`Song ${++n}`));
  page.mockResolvedValue(pageOk());
});

describe("migration 0008", () => {
  it("seeds anon_ai_daily and indexes account_attempts(at)", async () => {
    await env.DB.prepare("DELETE FROM settings WHERE key = 'anon_ai_daily'").run();
    const { results } = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'account_attempts'").all<any>();
    expect(results.map((r) => r.name)).toContain("idx_account_attempts_at");
    // default when the setting row is missing
    const anon = await makeUser("user", "a");
    expect(await planLimit(testEnv(), anon)).toBe(1);
  });
});

describe("planLimit", () => {
  it("anonymous 1, Google Free 3, Plus 50, expired Plus counts as Free; owner/admin unlimited", async () => {
    const e = testEnv();
    expect(await planLimit(e, await makeUser("user", "a"))).toBe(1);
    expect(await planLimit(e, await google())).toBe(3);
    expect(await planLimit(e, await google("plus"))).toBe(50);
    expect(await planLimit(e, await google("plus", Date.now() + 86_400_000))).toBe(50);
    expect(await planLimit(e, await google("plus", Date.now() - 1000))).toBe(3);
    expect(await planLimit(e, await makeUser("owner"))).toBeNull();
    expect(await planLimit(e, await makeUser("admin"))).toBeNull();
    // Plus set on an anonymous account still counts as Plus.
    const ap = await makeUser("user", "ap");
    await env.DB.prepare("UPDATE users SET plan = 'plus' WHERE id = ?").bind(ap.id).run();
    expect(await planLimit(e, ap)).toBe(50);
    await env.DB.prepare("UPDATE settings SET value='7' WHERE key='free_ai_daily'").run();
    expect(await planLimit(e, await google())).toBe(7);
  });
});

describe("a Free user's 4th AI action in a day is 429 plan_limit_reached, on every path", () => {
  const limited = { error: "plan_limit_reached", limit: 3, plan: "free" };

  it("three AI conversions pass, the fourth is refused with no model call", async () => {
    const u = await google();
    for (let i = 0; i < 3; i++) expect((await call(u, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(200);
    expect(await used(u)).toBe(3);
    const r = await call(u, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual(limited);
    expect(llm).toHaveBeenCalledTimes(3);
  });

  it("convert url, songs url, songs text, link import and .txt import", async () => {
    const u = await google();
    await setUsed(u, 3);
    for (const [path, body] of [
      ["/v1/convert", { url: "https://example.com/a" }],
      ["/v1/songs", { url: "https://example.com/b" }],
      ["/v1/songs", { text: CHORDED, ai: "on" }],
      ["/v1/import/links", { urls: ["https://example.com/c"] }],
    ] as const) {
      const r = await call(u, path, body);
      expect(r.status, path).toBe(429);
      expect(await r.json()).toEqual(limited);
    }
    const imp = await upload(u, { "a.txt": CHORDED });
    expect(imp.status).toBe(200);
    expect(((await imp.json()) as any).failed).toEqual([{ name: "a.txt", error: "plan_limit_reached" }]);
    expect(llm).not.toHaveBeenCalled();
    expect(page).not.toHaveBeenCalled();
    expect(sent).toHaveLength(0);
    expect(await used(u)).toBe(3);
  });

  it("a link job reserves one unit per URL, all or nothing", async () => {
    const u = await google();
    await setUsed(u, 1);
    const r = await call(u, "/v1/import/links", { urls: ["https://e.com/1", "https://e.com/2", "https://e.com/3"] });
    expect(r.status).toBe(429);
    expect(await used(u)).toBe(1);
    expect((await call(u, "/v1/import/links", { urls: ["https://e.com/1", "https://e.com/2"] })).status).toBe(202);
    expect(await used(u)).toBe(3);
  });

  it("auto mode keeps the plain conversion when the plan limit is used up", async () => {
    const u = await google();
    await setUsed(u, 3);
    const r = await call(u, "/v1/convert", { text: CHORDED, ai: "auto" });
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).via).toBe("plain");
    const imp = await upload(u, { "Words.txt": "only words\nno chords\n" }, "auto");
    expect(((await imp.json()) as any)).toMatchObject({ added: 1, failed: [] });
  });

  it("anonymous users get anon_ai_daily (1), reported as plan anonymous", async () => {
    const a = await makeUser("user", "anon");
    expect((await call(a, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(200);
    const r = await call(a, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "plan_limit_reached", limit: 1, plan: "anonymous" });
  });
});

describe("Plus and staff", () => {
  it("Plus allows 50; an expired Plus counts as Free", async () => {
    const p = await google("plus", Date.now() + 86_400_000);
    await setUsed(p, 49);
    expect((await call(p, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(200);
    const r = await call(p, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "plan_limit_reached", limit: 50, plan: "plus" });

    const x = await google("plus", Date.now() - 1);
    await setUsed(x, 3);
    const rx = await call(x, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(rx.status).toBe(429);
    expect(await rx.json()).toEqual({ error: "plan_limit_reached", limit: 3, plan: "free" });
  });

  it("owner and admin are not plan-limited, but the global ai_daily_limit still applies", async () => {
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='free_ai_daily'").run();
    for (const role of ["owner", "admin"] as const) {
      const s = await makeUser(role);
      for (let i = 0; i < 5; i++) expect((await call(s, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(200);
    }
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='ai_daily_limit'").run();
    const a = await makeUser("admin", "a2");
    const r = await call(a, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "ai_limit_reached" });
  });
});

describe("refunds follow modelCalled", () => {
  it("text: refunded before a model call, kept after one", async () => {
    const u = await google();
    expect((await call(u, "/v1/convert", { text: "just words\nmore words\n", ai: "on" })).status).toBe(422);
    expect(await used(u)).toBe(0);
    llm.mockResolvedValueOnce({ text: JSON.stringify({ error: "no_chords" }), providerId: "p", model: "m", ms: 1 });
    expect((await call(u, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(422);
    expect(await used(u)).toBe(1);
    llm.mockRejectedValueOnce(new ApiError(502, "ai_unavailable"));
    expect((await call(u, "/v1/convert", { text: CHORDED, ai: "on" })).status).toBe(502);
    expect(await used(u)).toBe(2);
  });

  it("url: a failed page fetch is refunded", async () => {
    const u = await google();
    page.mockRejectedValueOnce(new ApiError(502, "fetch_failed"));
    const r = await call(u, "/v1/songs", { url: "https://example.com/x" });
    expect(r.status).toBe(502);
    expect(await used(u)).toBe(0);
    expect(llm).not.toHaveBeenCalled();
  });

  it("link job: items failing before a model call are refunded; converted items are not", async () => {
    const u = await google();
    const r = await call(u, "/v1/import/links", { urls: ["https://example.com/a", "https://example.com/b"] });
    expect(r.status).toBe(202);
    expect(await used(u)).toBe(2);
    page.mockImplementation(async (_e, url) => {
      if (url.endsWith("/a")) throw new ApiError(502, "fetch_failed");
      return pageOk();
    });
    await runQueue(sent.map((m) => m.body));
    expect(await used(u)).toBe(1);
    // Redelivery of the failed item does not refund twice.
    await runQueue([sent[0].body]);
    expect(await used(u)).toBe(1);
  });

  it("anonymous link job: a failed fetch refunds both the user and the per-IP counter", async () => {
    await env.DB.prepare("DELETE FROM ip_ai_usage").run();
    const a = await makeUser("user", "anon-ip");
    const r = await worker.fetch!(new Request(ORIGIN + "/v1/import/links", {
      method: "POST",
      headers: { ...a.headers, "content-type": "application/json", "cf-connecting-ip": "198.51.100.23" },
      body: JSON.stringify({ urls: ["https://example.com/a"] }),
    }) as any, testEnv(), ctx);
    expect(r.status).toBe(202);
    const ipUsed = async () =>
      (await env.DB.prepare("SELECT count FROM ip_ai_usage WHERE ip_key = '198.51.100.23'").first<{ count: number }>())?.count ?? 0;
    expect(await used(a)).toBe(1);
    expect(await ipUsed()).toBe(1);
    page.mockRejectedValue(new ApiError(502, "fetch_failed"));
    await runQueue(sent.map((m) => m.body));
    expect(await used(a)).toBe(0);
    expect(await ipUsed()).toBe(0);
  });

  it("link job: items closed by the global AI limit are refunded", async () => {
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='ai_daily_limit'").run();
    const u = await google();
    await call(u, "/v1/import/links", { urls: ["https://example.com/a", "https://example.com/b"] });
    expect(await used(u)).toBe(2);
    await runQueue([sent[0].body]);
    expect(await used(u)).toBe(0);
  });
});

describe("per-minute AI request rate limit", () => {
  it(`more than ${AI_REQUESTS_PER_MINUTE} AI-capable requests a minute -> 429 rate_limited`, async () => {
    const p = await google("plus");
    for (let i = 0; i < AI_REQUESTS_PER_MINUTE; i++) {
      expect((await call(p, "/v1/import/links", { urls: [`https://e.com/${i}`] })).status).toBe(202);
    }
    let r = await call(p, "/v1/import/links", { urls: ["https://e.com/x"] });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
    r = await call(p, "/v1/convert", { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
    // A refused request reserved nothing.
    expect(await used(p)).toBe(AI_REQUESTS_PER_MINUTE);
    // Plain conversions are not AI-capable and still work; auto falls back to plain.
    expect((await call(p, "/v1/convert", { text: CHORDED, title: "T" })).status).toBe(200);
    r = await call(p, "/v1/convert", { text: CHORDED, ai: "auto" });
    expect(((await r.json()) as any).via).toBe("plain");
    // Another user is unaffected; requests older than a minute stop counting.
    expect((await call(await google("plus"), "/v1/import/links", { urls: ["https://e.com/y"] })).status).toBe(202);
    await env.DB.prepare("UPDATE ai_request_attempts SET at = at - 61000 WHERE user_id = ?").bind(p.id).run();
    expect((await call(p, "/v1/import/links", { urls: ["https://e.com/z"] })).status).toBe(202);
  });

  it("a parallel burst passes at most the per-minute cap", async () => {
    const p = await google("plus");
    const res = await Promise.all(
      Array.from({ length: AI_REQUESTS_PER_MINUTE + 4 }, (_, i) => call(p, "/v1/import/links", { urls: [`https://e.com/${i}`] })),
    );
    expect(res.filter((r) => r.status === 202)).toHaveLength(AI_REQUESTS_PER_MINUTE);
    expect(res.filter((r) => r.status === 429)).toHaveLength(4);
  });

  it(".txt import with AI counts as one request", async () => {
    const p = await google("plus");
    for (let i = 0; i < AI_REQUESTS_PER_MINUTE; i++) {
      expect((await call(p, "/v1/import/links", { urls: [`https://e.com/${i}`] })).status).toBe(202);
    }
    const r = await upload(p, { "a.txt": CHORDED, "b.txt": CHORDED });
    expect(((await r.json()) as any).failed).toEqual([
      { name: "a.txt", error: "rate_limited" }, { name: "b.txt", error: "rate_limited" },
    ]);
    expect(llm).not.toHaveBeenCalled();
  });
});

describe("GET /v1/me", () => {
  it("aiLeftToday follows the plan counter; planExpiresAt is null once Plus has expired", async () => {
    const u = await google();
    await setUsed(u, 2);
    expect(await (await get(u, "/v1/me")).json()).toMatchObject({ plan: "free", aiDailyLimit: 3, aiLeftToday: 1 });
    const a = await makeUser("user", "anon");
    expect(await (await get(a, "/v1/me")).json()).toMatchObject({ plan: "free", aiDailyLimit: 1, aiLeftToday: 1 });
    const x = await google("plus", 1000);
    expect(await (await get(x, "/v1/me")).json()).toMatchObject({ plan: "free", planExpiresAt: null, aiDailyLimit: 3 });
  });
});
