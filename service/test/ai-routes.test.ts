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
import { importFiles } from "../src/transfer/zip";
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

let owner: Awaited<ReturnType<typeof makeUser>>;
let member: Awaited<ReturnType<typeof makeUser>>;
let other: Awaited<ReturnType<typeof makeUser>>;

const cho = (title: string, artist = "Trad") =>
  `{title: ${title}}\n{artist: ${artist}}\n{key: G}\n\n[G]Amazing [C]grace how [G]sweet\n`;
const aiReply = (title: string, confidence = 0.93) => ({
  text: JSON.stringify({ chordpro: cho(title), language: "en", categories: ["hymn"], key: "G", confidence }),
  providerId: "p1", model: "m1", ms: 3,
});
const CHORDED = "G        C          G\nAmazing grace how sweet\n";
const pageOk = (text = CHORDED) => ({ text, via: "direct" as const, finalUrl: "https://example.com/" });

function call(method: string, path: string, who: { headers: Record<string, string> }, body?: unknown) {
  return worker.fetch!(
    new Request(ORIGIN + path, {
      method,
      headers: { ...who.headers, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    }) as any,
    testEnv(),
    ctx,
  );
}

function fakeBatch(bodies: ImportMessage[], attempts = 1) {
  const messages = bodies.map((body, i) => ({
    id: `m${i}`, timestamp: new Date(), body, attempts,
    ack: vi.fn(), retry: vi.fn(),
  }));
  return {
    queue: "orbittuner-import", messages,
    ackAll: vi.fn(), retryAll: vi.fn(),
  };
}

async function runQueue(bodies: ImportMessage[], attempts = 1) {
  const b = fakeBatch(bodies, attempts);
  await worker.queue(b as unknown as MessageBatch<ImportMessage>, testEnv());
  return b;
}

async function songCount() {
  return (await env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>())!.n;
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
    env.DB.prepare("UPDATE settings SET value='20' WHERE key='anon_ai_daily'"),
  ]);
  owner = await makeUser("owner", "o");
  member = await makeUser("user", "m");
  other = await makeUser("user", "x");
});

describe("POST /v1/convert", () => {
  it("url -> AI result", async () => {
    page.mockResolvedValueOnce(pageOk("Amazing grace chords\n" + CHORDED));
    llm.mockResolvedValueOnce(aiReply("Amazing Grace", 0.9));
    const r = await call("POST", "/v1/convert", owner, { url: "https://example.com/song" });
    expect(r.status).toBe(200);
    const j = (await r.json()) as any;
    expect(j).toEqual({ chordpro: cho("Amazing Grace"), language: "en", categories: ["hymn"], via: "ai", confidence: 0.9, provider: "p1" });
    expect(page.mock.calls[0][1]).toBe("https://example.com/song");
    expect(llm.mock.calls[0][1].user).toContain("Amazing grace chords");
  });

  it("text: auto uses plain when chords and title exist; on forces AI", async () => {
    const txt = "G  C\nAmazing grace\n";
    let r = await call("POST", "/v1/convert", member, { text: txt, title: "AG", ai: "auto" });
    let j = (await r.json()) as any;
    expect(j.via).toBe("plain");
    expect(j.chordpro).toContain("{title: AG}");
    expect(j.categories).toEqual([]);
    expect(typeof j.language).toBe("string");
    expect(llm).not.toHaveBeenCalled();

    llm.mockResolvedValueOnce(aiReply("AG", 0.7));
    r = await call("POST", "/v1/convert", member, { text: txt, ai: "on" });
    j = (await r.json()) as any;
    expect(j.via).toBe("ai");
    expect(j.confidence).toBe(0.7);

    r = await call("POST", "/v1/convert", member, { text: txt, ai: "maybe" });
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "invalid_input" });
  });

  it("bad url -> 400 bad_url without fetching; AI limit -> 429", async () => {
    let r = await call("POST", "/v1/convert", owner, { url: "http://localhost/x" });
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "bad_url" });
    expect(page).not.toHaveBeenCalled();

    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='ai_daily_limit'").run();
    page.mockResolvedValueOnce(pageOk());
    r = await call("POST", "/v1/convert", owner, { url: "https://example.com/a" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "ai_limit_reached" });
  });
});

describe("POST /v1/songs {url}", () => {
  it("owner -> published web song, even at low confidence (S4: owner/admin always publish)", async () => {
    page.mockResolvedValue(pageOk());
    llm.mockResolvedValueOnce(aiReply("High One", 0.85));
    let r = await call("POST", "/v1/songs", owner, { url: "https://example.com/high" });
    expect(r.status).toBe(201);
    let s = (await r.json()) as any;
    expect(s.status).toBe("published");
    expect(s.source).toBe("web");
    expect(s.sourceUrl).toBe("https://example.com/high");
    expect(s.categories).toEqual(["hymn"]);

    llm.mockResolvedValueOnce(aiReply("Low One", 0.79));
    r = await call("POST", "/v1/songs", owner, { url: "https://example.com/low" });
    s = (await r.json()) as any;
    expect(s.status).toBe("published");
  });

  it("member -> review at low confidence; duplicate -> 409", async () => {
    page.mockResolvedValue(pageOk());
    llm.mockResolvedValueOnce(aiReply("Member Song", 0.5));
    const r = await call("POST", "/v1/songs", member, { url: "https://example.com/m" });
    expect(r.status).toBe(201);
    const s = (await r.json()) as any;
    expect(s.status).toBe("review");
    expect(s.source).toBe("web");

    llm.mockResolvedValueOnce(aiReply("Member Song", 0.99));
    const d = await call("POST", "/v1/songs", member, { url: "https://example.com/m2" });
    expect(d.status).toBe(409);
    expect(((await d.json()) as any).songId).toBe(s.id);
  });

  it("member link imports count against the plan's daily limit", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='anon_ai_daily'").run();
    page.mockResolvedValue(pageOk());
    llm.mockResolvedValueOnce(aiReply("First", 0.9));
    expect((await call("POST", "/v1/songs", member, { url: "https://example.com/1" })).status).toBe(201);
    const r = await call("POST", "/v1/songs", member, { url: "https://example.com/2" });
    expect(r.status).toBe(429);
    expect(await r.json()).toMatchObject({ error: "plan_limit_reached", plan: "anonymous" });
    // owner is not limited
    llm.mockResolvedValueOnce(aiReply("Owner", 0.9));
    expect((await call("POST", "/v1/songs", owner, { url: "https://example.com/3" })).status).toBe(201);
  });

  it("text with ai:on -> import source, confidence decides status", async () => {
    llm.mockResolvedValueOnce(aiReply("Texty", 0.5));
    const r = await call("POST", "/v1/songs", member, { text: "messy\n" + CHORDED, ai: "on" });
    expect(r.status).toBe(201);
    const s = (await r.json()) as any;
    expect(s.source).toBe("import");
    expect(s.status).toBe("review");
  });
});

describe("link import jobs", () => {
  it("validates input", async () => {
    for (const body of [{}, { urls: [] }, { urls: "x" }, { urls: [5] }, { urls: Array.from({ length: 201 }, (_, i) => `https://e.com/${i}`) }]) {
      const r = await call("POST", "/v1/import/links", owner, body);
      expect(r.status).toBe(400);
      expect(await r.json()).toEqual({ error: "invalid_input" });
    }
  });

  it("3 urls (one fetch failure, one duplicate) -> per-item statuses; redelivery is idempotent", async () => {
    await call("POST", "/v1/songs", owner, { chordpro: cho("Already Here") });
    const r = await call("POST", "/v1/import/links", owner, {
      urls: ["https://example.com/a", "https://example.com/b", "https://example.com/c", "https://example.com/a", "http://localhost/x"],
    });
    expect(r.status).toBe(202);
    const { jobId } = (await r.json()) as any;
    expect(typeof jobId).toBe("string");
    expect(sent.map((m) => m.body)).toEqual([{ jobId, idx: 0 }, { jobId, idx: 1 }, { jobId, idx: 2 }]);

    page.mockImplementation(async (_e, url) => {
      if (url.endsWith("/a")) throw new ApiError(502, "fetch_failed");
      return pageOk(url + "\n" + CHORDED);
    });
    llm.mockImplementation(async (_e, req) =>
      aiReply(req.user.includes("example.com/b") ? "Already Here" : "Brand New", 0.95));

    const b = await runQueue(sent.map((m) => m.body));
    for (const m of b.messages) expect(m.ack).toHaveBeenCalled();
    expect(await songCount()).toBe(2);

    const g = await call("GET", `/v1/import/jobs/${jobId}`, owner);
    expect(g.status).toBe(200);
    const job = (await g.json()) as any;
    expect(job.id).toBe(jobId);
    expect(job.status).toBe("done");
    expect(job.total).toBe(4);
    expect(job.done).toBe(4);
    expect(job.items.map((i: any) => [i.url, i.status, i.error ?? null])).toEqual([
      ["https://example.com/a", "failed", "fetch_failed"],
      ["https://example.com/b", "duplicate", null],
      ["https://example.com/c", "done", null],
      ["http://localhost/x", "failed", "bad_url"],
    ]);
    expect(job.items[1].songId).toBeTruthy();
    const created = await env.DB.prepare("SELECT * FROM songs WHERE id = ?").bind(job.items[2].songId).first<any>();
    expect(created.source).toBe("web");
    expect(created.status).toBe("published");
    expect(created.source_url).toBe("https://example.com/c");

    // Redelivery: nothing is fetched, converted or inserted again.
    page.mockClear();
    llm.mockClear();
    const again = await runQueue(sent.map((m) => m.body), 2);
    for (const m of again.messages) expect(m.ack).toHaveBeenCalled();
    expect(page).not.toHaveBeenCalled();
    expect(llm).not.toHaveBeenCalled();
    expect(await songCount()).toBe(2);
    const j2 = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(j2.done).toBe(4);
  });

  it("AI limit reached mid-job -> remaining items limit_reached, no further AI", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='ai_daily_limit'").run();
    const urls = ["https://example.com/1", "https://example.com/2", "https://example.com/3", "https://example.com/4"];
    const { jobId } = (await (await call("POST", "/v1/import/links", owner, { urls })).json()) as any;
    page.mockResolvedValue(pageOk());
    let n = 0;
    llm.mockImplementation(async () => aiReply(`Song ${++n}`, 0.9));

    await runQueue(sent.slice(0, 2).map((m) => m.body));
    await runQueue(sent.slice(2).map((m) => m.body));
    expect(llm).toHaveBeenCalledTimes(1);
    expect(page).toHaveBeenCalledTimes(2);
    const job = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    // Messages in a batch run concurrently, so either of the first two may win the single AI slot.
    expect(job.items.map((i: any) => i.status).sort()).toEqual(["done", "limit_reached", "limit_reached", "limit_reached"]);
    expect(job.items.slice(2).map((i: any) => i.status)).toEqual(["limit_reached", "limit_reached"]);
    expect(job.status).toBe("done");
    expect(job.done).toBe(4);
  });

  it("ai result without a usable title -> failed ai_unparseable", async () => {
    const { jobId } = (await (await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/z"] })).json()) as any;
    page.mockResolvedValue(pageOk());
    llm.mockResolvedValue({ text: "nope", providerId: "p", model: "m", ms: 1 });
    await runQueue(sent.map((m) => m.body));
    const job = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(job.items[0]).toMatchObject({ status: "failed", error: "ai_unparseable" });
  });

  it("unexpected error -> retry, then failed on the last attempt", async () => {
    const { jobId } = (await (await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/boom"] })).json()) as any;
    page.mockRejectedValue(new Error("boom"));
    const b1 = await runQueue(sent.map((m) => m.body), 1);
    expect(b1.messages[0].retry).toHaveBeenCalled();
    expect(b1.messages[0].ack).not.toHaveBeenCalled();
    let job = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(job.items[0].status).toBe("queued");

    const b3 = await runQueue(sent.map((m) => m.body), 3);
    expect(b3.messages[0].ack).toHaveBeenCalled();
    job = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(job.items[0]).toMatchObject({ status: "failed", error: "internal" });
    expect(job.status).toBe("done");
  });

  it("members are limited by their plan's daily limit; low-confidence results land in review", async () => {
    await env.DB.prepare("UPDATE settings SET value='2' WHERE key='anon_ai_daily'").run();
    const over = await call("POST", "/v1/import/links", member, { urls: ["https://e.com/1", "https://e.com/2", "https://e.com/3"] });
    expect(over.status).toBe(429);
    expect(await over.json()).toMatchObject({ error: "plan_limit_reached", plan: "anonymous" });
    expect(sent).toHaveLength(0);

    const ok = await call("POST", "/v1/import/links", member, { urls: ["https://e.com/1", "https://e.com/2"] });
    expect(ok.status).toBe(202);
    const { jobId } = (await ok.json()) as any;
    page.mockResolvedValue(pageOk());
    let n = 0;
    llm.mockImplementation(async () => aiReply(`M ${++n}`, 0.5));
    await runQueue(sent.map((m) => m.body));
    const job = (await (await call("GET", `/v1/import/jobs/${jobId}`, member)).json()) as any;
    expect(job.items.map((i: any) => i.status)).toEqual(["review", "review"]);
    const ids = job.items.map((i: any) => i.songId);
    const { results } = await env.DB.prepare("SELECT status FROM songs WHERE id IN (?, ?)").bind(...ids).all<any>();
    expect(results.map((x) => x.status)).toEqual(["review", "review"]);

    const more = await call("POST", "/v1/import/links", member, { urls: ["https://e.com/9"] });
    expect(more.status).toBe(429);
  });

  it("only the job's creator can read it", async () => {
    const { jobId } = (await (await call("POST", "/v1/import/links", member, { urls: ["https://e.com/1"] })).json()) as any;
    expect((await call("GET", `/v1/import/jobs/${jobId}`, member)).status).toBe(200);
    const r = await call("GET", `/v1/import/jobs/${jobId}`, other);
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: "not_found" });
    expect((await call("GET", "/v1/import/jobs/nope", member)).status).toBe(404);
  });
});

describe("importFiles .txt with ai", () => {
  const u = (s: string) => new TextEncoder().encode(s);
  const ow = () => ({ id: owner.id, name: "o", role: "owner" as const, linked: false });

  it("on: AI per .txt, sequential; limit -> ai_limit_reached for the rest, others still import", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='ai_daily_limit'").run();
    let n = 0;
    llm.mockImplementation(async () => aiReply(`Ai Song ${++n}`, 0.95));
    const r = await importFiles(env.DB, ow(), [
      { name: "one.txt", data: u(CHORDED) },
      { name: "two.txt", data: u(CHORDED) },
      { name: "Third.cho", data: u(cho("Third")) },
    ], { ai: "on", env: testEnv() });
    expect(llm).toHaveBeenCalledTimes(1);
    expect(r.added).toBe(2);
    expect(r.failed).toEqual([{ name: "two.txt", error: "ai_limit_reached" }]);
    const { results } = await env.DB.prepare("SELECT title, source, status FROM songs ORDER BY title").all<any>();
    expect(results).toEqual([
      { title: "Ai Song 1", source: "import", status: "published" },
      { title: "Third", source: "import", status: "published" },
    ]);
  });

  it("auto: chorded .txt stays plain; lyrics-only .txt keeps its plain conversion", async () => {
    const r = await importFiles(env.DB, ow(), [
      { name: "Chorded.txt", data: u(CHORDED) },
      { name: "Lyrics.txt", data: u("just some lyrics\nno chords here\n") },
    ], { ai: "auto", env: testEnv() });
    expect(llm).not.toHaveBeenCalled();
    expect(r.added).toBe(2);
    expect(r.failed).toEqual([]);
  });

  it("low AI confidence -> published for the owner (S4); AI pending files count toward the song cap", async () => {
    llm.mockResolvedValueOnce(aiReply("Unsure", 0.5));
    const r = await importFiles(env.DB, ow(), [{ name: "u.txt", data: u(CHORDED) }], { ai: "on", env: testEnv() });
    expect(r.added).toBe(1);
    const row = await env.DB.prepare("SELECT status FROM songs").first<any>();
    expect(row.status).toBe("published");

    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('import_max_songs', '1')").run();
    try {
      llm.mockClear();
      await expect(importFiles(env.DB, ow(), [
        { name: "a.txt", data: u(CHORDED) }, { name: "b.txt", data: u(CHORDED) },
      ], { ai: "on", env: testEnv() })).rejects.toMatchObject({ code: "too_many_songs" });
      expect(llm).not.toHaveBeenCalled();
    } finally {
      await env.DB.prepare("DELETE FROM settings WHERE key = 'import_max_songs'").run();
    }
  });

  it("off (default) never calls AI", async () => {
    const r = await importFiles(env.DB, ow(), [{ name: "plain.txt", data: u("lyrics only\n") }]);
    expect(llm).not.toHaveBeenCalled();
    expect(r.added).toBe(1);
  });
});

// ---------- review fixes ----------
import { handleImportBatch } from "../src/ai/jobs";

describe("review fixes: consumer robustness", () => {
  async function job(urls: string[], who = owner) {
    const r = await call("POST", "/v1/import/links", who, { urls });
    return ((await r.json()) as any).jobId as string;
  }
  const view = async (id: string, who = owner) => (await (await call("GET", `/v1/import/jobs/${id}`, who)).json()) as any;

  it("processes a batch's messages concurrently", async () => {
    const id = await job(["https://example.com/1", "https://example.com/2"]);
    let inFlight = 0, maxInFlight = 0;
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; setTimeout(r, 1000); });
    page.mockImplementation(async () => {
      inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
      if (inFlight === 2) release();
      await gate;
      inFlight--;
      return pageOk();
    });
    let n = 0;
    llm.mockImplementation(async () => aiReply(`C ${++n}`, 0.9));
    await runQueue(sent.map((m) => m.body));
    expect(maxInFlight).toBe(2);
    expect((await view(id)).items.map((i: any) => i.status)).toEqual(["done", "done"]);
  });

  it("fetch+AI past the per-message deadline -> failed timeout", async () => {
    const id = await job(["https://example.com/slow"]);
    page.mockImplementation(() => new Promise(() => {}));
    const b = fakeBatch(sent.map((m) => m.body));
    await handleImportBatch(b as unknown as MessageBatch<unknown>, testEnv(), { deadlineMs: 30 });
    expect(b.messages[0].ack).toHaveBeenCalled();
    expect((await view(id)).items[0]).toMatchObject({ status: "failed", error: "timeout" });
  });

  it("items of a job idle 30+ min (never started) are swept to failed timeout (getJob and consumer)", async () => {
    const id = await job(["https://example.com/a", "https://example.com/b"]);
    await env.DB.prepare("UPDATE import_jobs SET created_at = ?, last_progress_at = ? WHERE id = ?")
      .bind(Date.now() - 31 * 60_000, Date.now() - 31 * 60_000, id).run();
    await runQueue([sent[0].body]);
    expect(page).not.toHaveBeenCalled();
    const v = await view(id);
    expect(v.items.map((i: any) => [i.status, i.error])).toEqual([["failed", "timeout"], ["failed", "timeout"]]);
    expect(v.status).toBe("done");
    expect(v.done).toBe(2);
  });

  it("ai_unavailable -> retry after 60 s; last attempt -> failed ai_unavailable", async () => {
    const id = await job(["https://example.com/u"]);
    page.mockResolvedValue(pageOk());
    llm.mockRejectedValue(new ApiError(502, "ai_unavailable"));
    const b1 = await runQueue(sent.map((m) => m.body), 1);
    expect(b1.messages[0].retry).toHaveBeenCalledWith({ delaySeconds: 60 });
    expect((await view(id)).items[0].status).toBe("queued");
    const b3 = await runQueue(sent.map((m) => m.body), 3);
    expect(b3.messages[0].ack).toHaveBeenCalled();
    expect((await view(id)).items[0]).toMatchObject({ status: "failed", error: "ai_unavailable" });
  });

  it("insert is skipped when the item was finished concurrently", async () => {
    const id = await job(["https://example.com/race"]);
    page.mockResolvedValue(pageOk());
    llm.mockImplementation(async () => {
      // A parallel delivery finishes the item while this one is converting.
      await env.DB.prepare("UPDATE import_job_items SET status='failed', error='x' WHERE job_id = ?").bind(id).run();
      return aiReply("Racy", 0.9);
    });
    await runQueue(sent.map((m) => m.body));
    expect(await songCount()).toBe(0);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM songs_fts").first<any>()).n).toBe(0);
  });

  it("parseUrls rejects more than 1000 entries outright", async () => {
    const r = await call("POST", "/v1/import/links", owner, { urls: Array(1001).fill("https://e.com/1") });
    expect(r.status).toBe(400);
    // duplicates within the cap are fine
    const ok = await call("POST", "/v1/import/links", owner, { urls: Array(1000).fill("https://e.com/1") });
    expect(ok.status).toBe(202);
  });
});

describe("review fixes: queue send failure", () => {
  it("nothing sent -> 503 queue_unavailable, items failed, member quota refunded", async () => {
    const broken = { ...(testEnv() as any), IMPORT_QUEUE: { sendBatch: async () => { throw new Error("down"); } } };
    const r = await worker.fetch!(new Request(ORIGIN + "/v1/import/links", {
      method: "POST", headers: { ...member.headers, "content-type": "application/json" },
      body: JSON.stringify({ urls: ["https://e.com/1", "https://e.com/2"] }),
    }) as any, broken, ctx);
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "queue_unavailable" });
    const usage = await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(member.id).first<any>();
    expect(usage?.count ?? 0).toBe(0);
    const items = await env.DB.prepare("SELECT status, error FROM import_job_items").all<any>();
    expect(items.results).toEqual([
      { status: "failed", error: "queue_unavailable" }, { status: "failed", error: "queue_unavailable" },
    ]);
    const j = await env.DB.prepare("SELECT status, done FROM import_jobs").first<any>();
    expect(j).toEqual({ status: "done", done: 2 });
  });

  it("partial send -> 202, unsent items failed queue_unavailable", async () => {
    let calls = 0;
    const flaky = { ...(testEnv() as any), IMPORT_QUEUE: {
      sendBatch: async (msgs: Sent[]) => { if (calls++ > 0) throw new Error("down"); for (const m of msgs) sent.push(m); },
    } };
    const urls = Array.from({ length: 101 }, (_, i) => `https://e.com/${i}`);
    const r = await worker.fetch!(new Request(ORIGIN + "/v1/import/links", {
      method: "POST", headers: { ...owner.headers, "content-type": "application/json" }, body: JSON.stringify({ urls }),
    }) as any, flaky, ctx);
    expect(r.status).toBe(202);
    const { jobId } = (await r.json()) as any;
    expect(sent).toHaveLength(100);
    const j = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(j.items[100]).toMatchObject({ status: "failed", error: "queue_unavailable" });
    expect(j.items[99].status).toBe("queued");
    expect(j.done).toBe(1);
  });
});

describe("review fixes: member AI accounting on the text path", () => {
  const u = (s: string) => new TextEncoder().encode(s);

  it("/v1/convert and /v1/songs text with AI count against the member's daily limit; owners exempt", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='anon_ai_daily'").run();
    let n = 0;
    llm.mockImplementation(async () => aiReply(`T ${++n}`, 0.9));
    expect((await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" })).status).toBe(200);
    let r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await r.json()).toMatchObject({ error: "plan_limit_reached", plan: "anonymous" });
    r = await call("POST", "/v1/songs", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(llm).toHaveBeenCalledTimes(1);
    // plain conversions are not counted
    expect((await call("POST", "/v1/convert", member, { text: CHORDED, title: "x", ai: "auto" })).status).toBe(200);
    // owner unaffected
    expect((await call("POST", "/v1/convert", owner, { text: CHORDED, ai: "on" })).status).toBe(200);
  });

  it(".txt import: member AI files beyond the daily limit fail plan_limit_reached", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='anon_ai_daily'").run();
    let n = 0;
    llm.mockImplementation(async () => aiReply(`F ${++n}`, 0.9));
    const mem = { id: member.id, name: "m", role: "user" as const, linked: false };
    const r = await importFiles(env.DB, mem, [
      { name: "a.txt", data: u(CHORDED) }, { name: "b.txt", data: u(CHORDED) },
    ], { ai: "on", env: testEnv() });
    expect(llm).toHaveBeenCalledTimes(1);
    expect(r.added).toBe(1);
    expect(r.failed).toEqual([{ name: "b.txt", error: "plan_limit_reached" }]);
  });

  it(".txt import: at most ai_import_sync_max AI files per upload, rest ai_batch_limit", async () => {
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('ai_import_sync_max', '1')").run();
    try {
      let n = 0;
      llm.mockImplementation(async () => aiReply(`G ${++n}`, 0.9));
      const r = await importFiles(env.DB, { id: owner.id, name: "o", role: "owner", linked: false }, [
        { name: "a.txt", data: u(CHORDED) }, { name: "b.txt", data: u(CHORDED) },
      ], { ai: "on", env: testEnv() });
      expect(llm).toHaveBeenCalledTimes(1);
      expect(r.added).toBe(1);
      expect(r.failed).toHaveLength(1);
      expect(r.failed[0]).toMatchObject({ name: "b.txt", error: "ai_batch_limit" });
      expect(r.failed[0].message).toMatch(/Links/);
    } finally {
      await env.DB.prepare("DELETE FROM settings WHERE key = 'ai_import_sync_max'").run();
    }
  });
});

describe("review fixes: limit stop vs in-flight items", () => {
  it("an item already converting keeps its result when another message hits the AI limit", async () => {
    await env.DB.prepare("UPDATE settings SET value='1' WHERE key='ai_daily_limit'").run();
    const r = await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/1", "https://example.com/2", "https://example.com/3"] });
    const { jobId } = (await r.json()) as any;
    page.mockResolvedValue(pageOk());
    let releaseFirst!: () => void;
    const firstHeld = new Promise<void>((res) => { releaseFirst = res; });
    llm.mockImplementation(async () => { await firstHeld; return aiReply("Kept", 0.9); });
    // Message 0 takes the only AI slot and waits inside the model call.
    const first = runQueue([sent[0].body]);
    await vi.waitFor(() => expect(llm).toHaveBeenCalledTimes(1));
    // Message 1 hits the limit while 0 is still in flight.
    await runQueue([sent[1].body]);
    releaseFirst();
    await first;
    const job = (await (await call("GET", `/v1/import/jobs/${jobId}`, owner)).json()) as any;
    expect(job.items.map((i: any) => i.status)).toEqual(["done", "limit_reached", "limit_reached"]);
    expect(await songCount()).toBe(1);
    expect(job.status).toBe("done");
  });
});

describe("round 2: stale sweep keyed on progress", () => {
  const view = async (id: string) => (await (await call("GET", `/v1/import/jobs/${id}`, owner)).json()) as any;

  it("an old job's in-flight item is never swept while getJob is polled; it still finishes done", async () => {
    const r = await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/slow"] });
    const { jobId } = (await r.json()) as any;
    const old = Date.now() - 31 * 60_000;
    page.mockResolvedValue(pageOk());
    let release!: () => void;
    const held = new Promise<void>((res) => { release = res; });
    llm.mockImplementation(async () => { await held; return aiReply("Slow One", 0.9); });
    const run = runQueue([sent[0].body]);
    await vi.waitFor(() => expect(llm).toHaveBeenCalledTimes(1));
    // The job is old and idle by every stamp, but its item started within the deadline: left alone.
    await env.DB.prepare("UPDATE import_jobs SET created_at = ?, last_progress_at = ? WHERE id = ?").bind(old, old, jobId).run();
    expect((await view(jobId)).items[0].status).toBe("queued");
    release();
    await run;
    const v = await view(jobId);
    expect(v.items[0].status).toBe("done");
    expect(v.status).toBe("done");
  });

  it("a fresh job with a long backlog is not swept even when created long ago", async () => {
    const r = await call("POST", "/v1/import/links", owner, { urls: ["https://example.com/a", "https://example.com/b"] });
    const { jobId } = (await r.json()) as any;
    await env.DB.prepare("UPDATE import_jobs SET created_at = ? WHERE id = ?").bind(Date.now() - 2 * 3600_000, jobId).run();
    page.mockResolvedValue(pageOk());
    llm.mockImplementation(async () => aiReply("Backlog", 0.9));
    await runQueue([sent[0].body]);
    const v = await view(jobId);
    expect(v.items.map((i: any) => i.status)).toEqual(["done", "queued"]);
  });
});

describe("round 2: member AI unit refunds only when no model call happened", () => {
  const usage = async () =>
    (await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(member.id).first<any>())?.count ?? 0;

  it("model-returned no_chords and ai_unavailable after a call are not refunded", async () => {
    llm.mockResolvedValueOnce({ text: JSON.stringify({ error: "no_chords" }), providerId: "p", model: "m", ms: 1 });
    let r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(422);
    expect(await usage()).toBe(1);
    llm.mockRejectedValueOnce(new ApiError(502, "ai_unavailable"));
    r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(502);
    expect(await usage()).toBe(2);
  });

  it("errors before any model call are refunded", async () => {
    // pre-check no_chords (lyrics only)
    let r = await call("POST", "/v1/convert", member, { text: "just words\nmore words\n", ai: "on" });
    expect(r.status).toBe(422);
    expect(await usage()).toBe(0);
    // service AI limit
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='ai_daily_limit'").run();
    r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(await usage()).toBe(0);
    expect(llm).not.toHaveBeenCalled();
  });
});

describe("round 2: auto mode falls back to plain when a member is over their AI limit", () => {
  const u = (s: string) => new TextEncoder().encode(s);
  beforeEach(async () => {
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='anon_ai_daily'").run();
  });

  it("/v1/convert and /v1/songs text", async () => {
    let r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "auto" });
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).via).toBe("plain");
    r = await call("POST", "/v1/songs", member, { text: CHORDED, ai: "auto" });
    // plain conversion has no title: the normal validation error, not 429
    expect(r.status).toBe(422);
    expect(await r.json()).toEqual({ error: "missing_title" });
    r = await call("POST", "/v1/convert", member, { text: CHORDED, ai: "on" });
    expect(r.status).toBe(429);
    expect(llm).not.toHaveBeenCalled();
  });

  it(".txt imports", async () => {
    const r = await importFiles(env.DB, { id: member.id, name: "m", role: "user", linked: false }, [
      { name: "Words.txt", data: u("only words\nno chords\n") },
    ], { ai: "auto", env: testEnv() });
    expect(r.added).toBe(1);
    expect(r.failed).toEqual([]);
    expect(llm).not.toHaveBeenCalled();
  });
});

describe("round 2: compensation batch failure still refunds and returns 503", () => {
  it("queue down + compensation batch throws -> 503, quota refunded", async () => {
    const real = env.DB;
    let batches = 0;
    // Batch 1 is the per-minute rate check, 2 the job insert, 3 the compensation.
    const db = new Proxy(real, {
      get(t, k) {
        if (k === "batch") return async (s: D1PreparedStatement[]) => { if (++batches === 3) throw new Error("d1 down"); return t.batch(s); };
        const v = (t as any)[k];
        return typeof v === "function" ? v.bind(t) : v;
      },
    });
    const broken = { ...(testEnv() as any), DB: db, IMPORT_QUEUE: { sendBatch: async () => { throw new Error("down"); } } };
    const r = await worker.fetch!(new Request(ORIGIN + "/v1/import/links", {
      method: "POST", headers: { ...member.headers, "content-type": "application/json" },
      body: JSON.stringify({ urls: ["https://e.com/1"] }),
    }) as any, broken, ctx);
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "queue_unavailable" });
    const row = await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(member.id).first<any>();
    expect(row?.count ?? 0).toBe(0);
  });
});
