import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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
import { normaliseQuery, rankCandidates } from "../src/ai/discover";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

const llm = vi.mocked(complete);
const page = vi.mocked(fetchPage);

type Sent = { body: ImportMessage };
let sent: Sent[];
const fakeQueue = {
  send: async (body: ImportMessage) => { sent.push({ body }); },
  sendBatch: async (msgs: Iterable<Sent>) => { for (const m of msgs) sent.push(m); },
};
const testEnv = () => ({ ...(env as unknown as Env), IMPORT_QUEUE: fakeQueue }) as unknown as Env;
const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;

function call(method: string, path: string, who: { headers: Record<string, string> }, body?: unknown, ip = "203.0.113.7") {
  return worker.fetch!(
    new Request(ORIGIN + path, {
      method,
      headers: { ...who.headers, "content-type": "application/json", "cf-connecting-ip": ip },
      body: body === undefined ? undefined : JSON.stringify(body),
    }) as any,
    testEnv(),
    ctx,
  );
}

async function runQueue(bodies: ImportMessage[]) {
  const messages = bodies.map((body, i) => ({
    id: `m${i}`, timestamp: new Date(), body, attempts: 1, ack: vi.fn(), retry: vi.fn(),
  }));
  await worker.queue({ queue: "q", messages, ackAll: vi.fn(), retryAll: vi.fn() } as unknown as MessageBatch<ImportMessage>, testEnv());
}

const cho = (title: string) => `{title: ${title}}\n{artist: Trad}\n{key: G}\n\n[G]Amazing [C]grace how [G]sweet\n`;
const aiReply = (title: string, confidence = 0.93) => ({
  text: JSON.stringify({ chordpro: cho(title), language: "en", categories: ["hymn"], key: "G", confidence }),
  providerId: "p1", model: "m1", ms: 3,
});

/** Apify Google Search output: one record per SERP page with organicResults. */
const serp = (urls: string[]) => [{ searchQuery: { term: "x" }, organicResults: urls.map((url, i) => ({ url, title: `r${i}`, position: i + 1 })) }];

let apifyCalls: { url: string; init: RequestInit | undefined }[];
let apifyReply: () => Response | Promise<Response>;
beforeEach(() => {
  apifyCalls = [];
  apifyReply = () => Response.json(serp([]));
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url.startsWith("https://api.apify.com/")) {
      apifyCalls.push({ url, init });
      return apifyReply();
    }
    throw new Error("unexpected fetch " + url);
  });
});
afterEach(() => vi.restoreAllMocks());

const usage = async (userId: string) =>
  (await env.DB.prepare("SELECT COALESCE(SUM(count), 0) AS n FROM user_ai_usage WHERE user_id = ?").bind(userId).first<{ n: number }>())!.n;

const today = () => new Date().toISOString().slice(0, 10);
const searches = async (scope: string, key: string) =>
  (await env.DB.prepare("SELECT COALESCE(SUM(count), 0) AS n FROM search_usage WHERE scope = ? AND key = ?")
    .bind(scope, key).first<{ n: number }>())!.n;

let owner: Awaited<ReturnType<typeof makeUser>>;
let anon: Awaited<ReturnType<typeof makeUser>>;
let linked: Awaited<ReturnType<typeof makeUser>>;

beforeEach(async () => {
  llm.mockReset();
  page.mockReset();
  sent = [];
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM ai_usage"), env.DB.prepare("DELETE FROM user_ai_usage"),
    env.DB.prepare("DELETE FROM ai_request_attempts"), env.DB.prepare("DELETE FROM discover_cache"), env.DB.prepare("DELETE FROM search_usage"),
    env.DB.prepare("UPDATE settings SET value='2' WHERE key='search_daily_anon'"),
    env.DB.prepare("UPDATE settings SET value='10' WHERE key='search_daily_free'"),
    env.DB.prepare("UPDATE settings SET value='30' WHERE key='search_daily_per_ip'"),
    env.DB.prepare("UPDATE settings SET value='500' WHERE key='search_daily_global'"),
    env.DB.prepare("DELETE FROM import_jobs"), env.DB.prepare("DELETE FROM import_job_items"),
    env.DB.prepare("UPDATE settings SET value='100' WHERE key='ai_daily_limit'"),
    env.DB.prepare("UPDATE settings SET value='3' WHERE key='anon_ai_daily'"),
    env.DB.prepare("UPDATE settings SET value='3' WHERE key='free_ai_daily'"),
    env.DB.prepare("UPDATE settings SET value='apify~google-search-scraper' WHERE key='apify_search_actor'"),
  ]);
  owner = await makeUser("owner", "o");
  anon = await makeUser("user", "a");
  linked = await makeUser("user", "l", { linked: true });
});

const SIX = [
  "https://www.youtube.com/watch?v=abc",
  "https://example-lyrics.com/dil-se-gao",
  "https://m.facebook.com/post/1",
  "http://10.0.0.1/private",
  "https://open.spotify.com/track/1",
  "https://blog.example.org/dil-se-gao-chords",
  "https://www.ultimate-guitar.com/tab/x/dil-se-gao-chords-123",
  "https://another.example.net/dil-se-gao",
];

describe("POST /v1/discover", () => {
  it("validates q", async () => {
    for (const body of [
      {}, { q: "a" }, { q: "x".repeat(121) }, { q: 5 }, { q: "ok song", lang: "english!" }, { q: "ok song", lang: "fr" },
      { q: "https://example.com/song" }, { q: "www.example.com/song" }, { q: "ftp://x.example.com" },
      { q: "\uFDFA".repeat(10) }, // 10 chars raw, 180 after NFKC
      { q: "\u200b\u200ba\u200b" }, // format characters stripped -> 1 char
    ]) {
      const r = await call("POST", "/v1/discover", owner, body);
      expect(r.status).toBe(400);
      expect(await r.json()).toEqual({ error: "invalid_input" });
    }
    expect(apifyCalls).toHaveLength(0);
  });

  it("searches '<q> chords', filters social/video/private hosts, ranks chord sites first, keeps top 3", async () => {
    apifyReply = () => Response.json(serp(SIX));
    const r = await call("POST", "/v1/discover", owner, { q: "  Dil Se Gao  " });
    expect(r.status).toBe(202);
    const j = (await r.json()) as any;
    expect(j.candidates).toBe(3);
    expect(typeof j.jobId).toBe("string");

    expect(apifyCalls).toHaveLength(1);
    expect(apifyCalls[0].url).toMatch(/^https:\/\/api\.apify\.com\/v2\/acts\/apify~google-search-scraper\/run-sync-get-dataset-items\?/);
    expect((apifyCalls[0].init!.headers as Record<string, string>).authorization).toBe("Bearer test-apify-token-9012");
    const input = JSON.parse(apifyCalls[0].init!.body as string);
    expect(input.queries).toBe("dil se gao chords");
    expect(input.maxPagesPerQuery).toBe(1);

    expect(sent.map((m) => m.body)).toEqual([{ jobId: j.jobId, idx: 0 }, { jobId: j.jobId, idx: 1 }, { jobId: j.jobId, idx: 2 }]);
    const g = (await (await call("GET", `/v1/import/jobs/${j.jobId}`, owner)).json()) as any;
    expect(g.kind).toBe("discover");
    expect(g.query).toBe("dil se gao");
    expect(g.items.map((i: any) => i.url)).toEqual([
      "https://www.ultimate-guitar.com/tab/x/dil-se-gao-chords-123",
      "https://example-lyrics.com/dil-se-gao",
      "https://blog.example.org/dil-se-gao-chords",
    ]);
  });

  it("link jobs report kind links", async () => {
    const r = await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/a"] });
    const { jobId } = (await r.json()) as any;
    const g = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(g.kind).toBe("links");
    expect(g.query).toBeUndefined();
  });

  it("an identical normalised query within 24 h reuses the cached candidates (no Apify call)", async () => {
    apifyReply = () => Response.json(serp(SIX));
    const a = (await (await call("POST", "/v1/discover", owner, { q: "Dil Se Gao" })).json()) as any;
    const r = await call("POST", "/v1/discover", linked, { q: "dil   se GAO chords" });
    expect(r.status).toBe(202);
    const b = (await r.json()) as any;
    expect(apifyCalls).toHaveLength(1);
    expect(b.jobId).not.toBe(a.jobId); // a new job for this user; job visibility stays per owner
    const ga = (await (await call("GET", `/v1/import/jobs/${a.jobId}`, owner)).json()) as any;
    const gb = (await (await call("GET", `/v1/import/jobs/${b.jobId}`, linked)).json()) as any;
    expect(gb.items.map((i: any) => i.url)).toEqual(ga.items.map((i: any) => i.url));
    expect((await call("GET", `/v1/import/jobs/${a.jobId}`, linked)).status).toBe(404);

    // Older than 24 h: searched again.
    await env.DB.prepare("UPDATE discover_cache SET created_at = ?, expires_at = ?")
      .bind(Date.now() - 25 * 3600_000, Date.now() - 3600_000).run();
    await call("POST", "/v1/discover", owner, { q: "dil se gao" });
    expect(apifyCalls).toHaveLength(2);
  });

  it("no usable results -> 200 {jobId: null, results: 0}, nothing reserved", async () => {
    apifyReply = () => Response.json(serp(["https://www.youtube.com/watch?v=1", "http://localhost/x", "ftp://a.example.com/x"]));
    const r = await call("POST", "/v1/discover", anon, { q: "nothing here" });
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ jobId: null, results: 0 });
    expect(await usage(anon.id)).toBe(0);
    expect(sent).toHaveLength(0);
  });

  it("all results filtered out are cached for 1 h only; zero organic results are not cached", async () => {
    apifyReply = () => Response.json(serp(["https://www.youtube.com/watch?v=1"]));
    await call("POST", "/v1/discover", owner, { q: "filtered song" });
    const row = await env.DB.prepare("SELECT urls, created_at, expires_at FROM discover_cache").first<any>();
    expect(row.urls).toBe("[]");
    expect(row.expires_at - row.created_at).toBe(3600_000);
    await call("POST", "/v1/discover", owner, { q: "filtered song" });
    expect(apifyCalls).toHaveLength(1);

    apifyReply = () => Response.json(serp([]));
    const r = await call("POST", "/v1/discover", owner, { q: "unknown song" });
    expect(await r.json()).toEqual({ jobId: null, results: 0 });
    await call("POST", "/v1/discover", owner, { q: "unknown song" });
    expect(apifyCalls).toHaveLength(3);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM discover_cache").first<{ n: number }>())!.n).toBe(1);
  });

  it("found candidates are cached for 24 h", async () => {
    apifyReply = () => Response.json(serp(SIX));
    await call("POST", "/v1/discover", owner, { q: "dil se gao" });
    const row = await env.DB.prepare("SELECT created_at, expires_at FROM discover_cache").first<any>();
    expect(row.expires_at - row.created_at).toBe(24 * 3600_000);
  });

  it("Apify failure -> 502 search_failed, nothing charged or cached", async () => {
    apifyReply = () => new Response("boom", { status: 500 });
    const r = await call("POST", "/v1/discover", anon, { q: "some song" });
    expect(r.status).toBe(502);
    expect(await r.json()).toEqual({ error: "search_failed" });
    expect(await usage(anon.id)).toBe(0);
    expect(await searches("user", anon.id)).toBe(0); // search unit refunded
    expect(await searches("ip", "203.0.113.7")).toBe(0);
    expect(await searches("global", "*")).toBe(0);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM discover_cache").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("race: the AI units run out during the search -> duplicates kept, rest limit_reached", async () => {
    // A visible song already imported from one candidate.
    const made = (await (await call("POST", "/v1/songs", owner, { chordpro: cho("Dil Se Gao") })).json()) as any;
    const songId: string = made.id ?? made.song?.id;
    await env.DB.prepare("UPDATE songs SET source_url = ? WHERE id = ?").bind(SIX[6], songId).run();
    apifyReply = async () => {
      // A concurrent request spends the user's last units while the search runs.
      await env.DB.prepare("INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, 3)")
        .bind(linked.id, new Date().toISOString().slice(0, 10)).run();
      return Response.json(serp(SIX));
    };
    const r = await call("POST", "/v1/discover", linked, { q: "dil se gao" });
    expect(r.status).toBe(202);
    const j = (await r.json()) as any;
    expect(sent).toHaveLength(0);
    const g = (await (await call("GET", `/v1/import/jobs/${j.jobId}`, linked)).json()) as any;
    expect(g.items.map((i: any) => [i.status, i.songId ?? null])).toEqual([
      ["duplicate", songId], ["limit_reached", null], ["limit_reached", null],
    ]);
    expect(await usage(linked.id)).toBe(3);
  });

  it("reserves one unit per queued page and refunds pages that never reached a model", async () => {
    apifyReply = () => Response.json(serp(SIX));
    const r = await call("POST", "/v1/discover", anon, { q: "dil se gao" });
    expect(r.status).toBe(202);
    expect(await usage(anon.id)).toBe(3);

    page.mockImplementation(async (_e, url) => {
      if (url.includes("ultimate-guitar")) return { text: "G C\nAmazing grace\n", via: "direct" as const, finalUrl: url };
      throw new ApiError(502, "fetch_failed");
    });
    llm.mockResolvedValue(aiReply("Dil Se Gao"));
    await runQueue(sent.map((m) => m.body));
    expect(await usage(anon.id)).toBe(1); // two fetch failures refunded
    // Anonymous contributions land in review.
    const s = await env.DB.prepare("SELECT status, created_by, source_url FROM songs").all<any>();
    expect(s.results).toEqual([{ status: "review", created_by: anon.id, source_url: SIX[6] }]);
  });

  it("partial quota: 1 unit left queues 1 page, the rest are limit_reached", async () => {
    apifyReply = () => Response.json(serp(SIX));
    await env.DB.prepare("INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, 2)")
      .bind(linked.id, new Date().toISOString().slice(0, 10)).run();
    const r = await call("POST", "/v1/discover", linked, { q: "dil se gao" });
    expect(r.status).toBe(202);
    const j = (await r.json()) as any;
    expect(j.candidates).toBe(3);
    expect(sent).toHaveLength(1);
    expect(await usage(linked.id)).toBe(3);
    const g = (await (await call("GET", `/v1/import/jobs/${j.jobId}`, linked)).json()) as any;
    expect(g.items.map((i: any) => [i.status, i.error ?? null])).toEqual([
      ["queued", null], ["limit_reached", "plan_limit_reached"], ["limit_reached", "plan_limit_reached"],
    ]);
    expect(g.total).toBe(3);
    expect(g.done).toBe(2);
  });

  it("0 units left -> 429 plan_limit_reached before any search", async () => {
    await env.DB.prepare("INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, 3)")
      .bind(linked.id, new Date().toISOString().slice(0, 10)).run();
    const r = await call("POST", "/v1/discover", linked, { q: "dil se gao" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "plan_limit_reached", limit: 3, plan: "free" });
    expect(apifyCalls).toHaveLength(0);
  });

  it("counts toward the per-minute AI request limit", async () => {
    const now = Date.now();
    for (let i = 0; i < 10; i++) {
      await env.DB.prepare("INSERT INTO ai_request_attempts (user_id, at) VALUES (?, ?)").bind(anon.id, now - 1000).run();
    }
    const r = await call("POST", "/v1/discover", anon, { q: "dil se gao" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
    expect(apifyCalls).toHaveLength(0);
  });

  it("a page already imported as a visible song is a free duplicate (no unit, no queue message)", async () => {
    apifyReply = () => Response.json(serp(SIX));
    await call("POST", "/v1/discover", owner, { q: "dil se gao" });
    page.mockImplementation(async (_e, url) => ({ text: "G C\nAmazing grace\n", via: "direct" as const, finalUrl: url }));
    let n = 0;
    llm.mockImplementation(async () => aiReply(`Song ${n++}`));
    await runQueue(sent.map((m) => m.body));
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM songs WHERE status='published'").first<{ n: number }>())!.n).toBe(3);

    sent = [];
    const r = await call("POST", "/v1/discover", linked, { q: "dil se gao" });
    const j = (await r.json()) as any;
    expect(r.status).toBe(202);
    expect(sent).toHaveLength(0);
    expect(await usage(linked.id)).toBe(0);
    const g = (await (await call("GET", `/v1/import/jobs/${j.jobId}`, linked)).json()) as any;
    expect(g.status).toBe("done");
    expect(g.items.every((i: any) => i.status === "duplicate" && typeof i.songId === "string")).toBe(true);
  });

  it("search quota exhausted -> 429 search_limit_reached, no Apify call, no AI unit", async () => {
    await env.DB.prepare("INSERT INTO search_usage (scope, key, day, count) VALUES ('user', ?, ?, 2)").bind(anon.id, today()).run();
    const r = await call("POST", "/v1/discover", anon, { q: "dil se gao" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "search_limit_reached", limit: 2, plan: "anonymous" });
    expect(apifyCalls).toHaveLength(0);
    expect(await usage(anon.id)).toBe(0);
    expect(await searches("global", "*")).toBe(0); // the other scopes were rolled back
    expect(await searches("ip", "203.0.113.7")).toBe(0);
  });

  it("an uncached search costs one search unit (user, IP, global); a cache hit costs none", async () => {
    apifyReply = () => Response.json(serp(SIX));
    await call("POST", "/v1/discover", anon, { q: "dil se gao" });
    expect(await searches("user", anon.id)).toBe(1);
    expect(await searches("ip", "203.0.113.7")).toBe(1);
    expect(await searches("global", "*")).toBe(1);
    await env.DB.prepare("DELETE FROM user_ai_usage").run();
    await call("POST", "/v1/discover", anon, { q: "Dil Se Gao chords" });
    expect(apifyCalls).toHaveLength(1);
    expect(await searches("user", anon.id)).toBe(1);
    expect(await searches("global", "*")).toBe(1);
  });

  it("owner/admin have no per-user or per-IP search limit but count toward the global budget", async () => {
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='search_daily_per_ip'").run();
    await call("POST", "/v1/discover", owner, { q: "dil se gao" });
    expect(apifyCalls).toHaveLength(1);
    expect(await searches("user", owner.id)).toBe(0);
    expect(await searches("global", "*")).toBe(1);
  });

  it("per-IP daily search cap across accounts", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='search_daily_per_ip'").run();
    expect((await call("POST", "/v1/discover", anon, { q: "song one" })).status).toBe(200);
    const r = await call("POST", "/v1/discover", linked, { q: "song two" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "search_limit_reached", limit: 1, plan: "free" });
    expect(await searches("user", linked.id)).toBe(0);
    expect((await call("POST", "/v1/discover", linked, { q: "song two" }, "198.51.100.9")).status).toBe(200);
    expect(apifyCalls).toHaveLength(2);
  });

  it("global daily search budget -> 503 search_unavailable, even for the owner", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='search_daily_global'").run();
    apifyReply = () => Response.json(serp(SIX));
    expect((await call("POST", "/v1/discover", anon, { q: "song one" })).status).toBe(202);
    const r = await call("POST", "/v1/discover", owner, { q: "song two" });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "search_unavailable" });
    expect(apifyCalls).toHaveLength(1);
    // Cached queries still work when the budget is spent.
    expect((await call("POST", "/v1/discover", owner, { q: "song one" })).status).toBe(202);
  });

  it("an invalid apify_search_actor setting falls back to the default actor", async () => {
    await env.DB.prepare("UPDATE settings SET value='https://evil/x' WHERE key='apify_search_actor'").run();
    await call("POST", "/v1/discover", owner, { q: "dil se gao" });
    expect(apifyCalls[0].url).toContain("/acts/apify~google-search-scraper/");
  });
});

describe("discover helpers", () => {
  it("normaliseQuery folds case, spaces and a trailing 'chords'", () => {
    expect(normaliseQuery("  Dil   Se Gao CHORDS ")).toBe("dil se gao");
    expect(normaliseQuery("Ｄｉｌ se gao")).toBe("dil se gao");
    expect(normaliseQuery("dil\u200b se\u00ad gao")).toBe("dil se gao");
  });

  it("rankCandidates keeps first occurrence, drops blocked hosts and non-http", () => {
    expect(rankCandidates([
      "https://music.apple.com/x", "https://www.jiosaavn.com/x", "https://gaana.com/x", "https://wynk.in/x",
      "https://en.wikipedia.org/wiki/X", "https://in.pinterest.com/x", "https://www.tiktok.com/x",
      "https://www.instagram.com/x", "https://youtu.be/x", "mailto:a@b.c",
      "https://vimeo.com/1", "https://www.dailymotion.com/1", "https://www.reddit.com/r/x", "https://www.threads.net/x",
      "https://in.linkedin.com/x", "https://www.shazam.com/x", "https://www.hungama.com/x", "https://www.google.co.in/x",
      "https://lh3.googleusercontent.com/x",
      "https://a.example.com/1", "https://a.example.com/1", "https://www.e-chords.com/x",
    ])).toEqual(["https://www.e-chords.com/x", "https://a.example.com/1"]);
  });
});
