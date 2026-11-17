import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import app from "../src/index";
import { clearSongs, makeUser, ORIGIN } from "./helpers";
import amazing from "./fixtures/chordpro/amazing-grace.cho?raw";
import noTitle from "./fixtures/chordpro/no-title.cho?raw";
import colTxt from "./fixtures/chordpro/chords-over-lyrics.txt?raw";

let owner: Awaited<ReturnType<typeof makeUser>>;
let member: Awaited<ReturnType<typeof makeUser>>;
let other: Awaited<ReturnType<typeof makeUser>>;

beforeEach(async () => {
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM reports"),
  ]);
  owner = await makeUser("owner", "o");
  member = await makeUser("user", "m");
  other = await makeUser("user", "x");
});

function post(path: string, who: { headers: Record<string, string> }, body: unknown, raw = false) {
  return SELF.fetch(ORIGIN + path, {
    method: "POST",
    headers: { ...who.headers, "content-type": "application/json" },
    body: raw ? (body as string) : JSON.stringify(body),
  });
}

describe("POST /v1/songs", () => {
  it("owner publishes, member goes to review", async () => {
    const r = await post("/v1/songs", owner, { chordpro: amazing });
    expect(r.status).toBe(201);
    const s = (await r.json()) as any;
    expect(s.status).toBe("published");
    expect(s.source).toBe("owner");
    const m = await post("/v1/songs", member, { chordpro: "{title: Mine}\n[C]la\n", categories: ["a"], language: "hi" });
    expect(m.status).toBe(201);
    const ms = (await m.json()) as any;
    expect(ms.status).toBe("review");
    expect(ms.language).toBe("hi");
    expect(ms.categories).toEqual(["a"]);
    const row = await env.DB.prepare("SELECT created_by FROM songs WHERE id=?").bind(ms.id).first<any>();
    expect(row.created_by).toBe(member.id);
  });

  it("duplicate -> 409 with first id", async () => {
    const first = (await (await post("/v1/songs", owner, { chordpro: amazing })).json()) as any;
    const r = await post("/v1/songs", owner, { chordpro: amazing });
    expect(r.status).toBe(409);
    expect(await r.json()).toEqual({ error: "duplicate", songId: first.id });
    // another member sees the published one too
    const r2 = await post("/v1/songs", member, { chordpro: amazing });
    expect(r2.status).toBe(409);
  });

  it("duplicate of a hidden/review song is not leaked to other members", async () => {
    const reviewOnly = "{title: Two Chords}\n[C]la [G]la\n"; // fails the publish rule -> review
    const mine = (await (await post("/v1/songs", member, { chordpro: reviewOnly })).json()) as any;
    expect(mine.status).toBe("review");
    const r = await post("/v1/songs", other, { chordpro: reviewOnly });
    expect(r.status).toBe(201);
    // the creator does see their own
    const again = await post("/v1/songs", member, { chordpro: reviewOnly });
    expect(again.status).toBe(409);
    expect(((await again.json()) as any).songId).toBe(mine.id);
  });

  it("converts plain text with title/artist", async () => {
    const r = await post("/v1/songs", owner, { text: colTxt, title: "X", artist: "Y" });
    expect(r.status).toBe(201);
    const s = (await r.json()) as any;
    expect(s.chordpro).toContain("{title: X}");
    expect(s.artist).toBe("Y");
  });

  it("validation and shape errors", async () => {
    let r = await post("/v1/songs", owner, { chordpro: noTitle });
    expect(r.status).toBe(422);
    expect(await r.json()).toEqual({ error: "missing_title" });
    r = await post("/v1/songs", owner, { text: colTxt });
    expect(r.status).toBe(422);
    r = await post("/v1/songs", owner, { chordpro: "   " });
    expect(r.status).toBe(422);
    expect(await r.json()).toEqual({ error: "empty" });
    r = await post("/v1/songs", owner, { url: "http://localhost/x" });
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "bad_url" });
    r = await post("/v1/songs", owner, { foo: 1 });
    expect(r.status).toBe(400);
    r = await post("/v1/songs", owner, "not json", true);
    expect(r.status).toBe(400);
  });

  it("rejects bad field types with 400 invalid_input", async () => {
    for (const body of [
      { chordpro: 5 },
      { chordpro: amazing, categories: "x" },
      { chordpro: amazing, categories: [1] },
      { chordpro: amazing, language: 3 },
      { chordpro: amazing, language: "x".repeat(17) },
      { text: "a", title: 5 },
      [1, 2],
    ]) {
      const r = await post("/v1/songs", owner, body);
      expect(r.status).toBe(400);
      expect(await r.json()).toEqual({ error: "invalid_input" });
    }
  });

  it("caps sizes with 413", async () => {
    const big = "x".repeat(200_001);
    for (const body of [{ chordpro: big }, { text: big, title: "t" }]) {
      const r = await post("/v1/songs", owner, body);
      expect(r.status).toBe(413);
      expect(await r.json()).toEqual({ error: "too_large" });
    }
    const r = await post("/v1/songs", owner, { chordpro: amazing, categories: Array(60_000).fill("abcdefghij") });
    expect(r.status).toBe(413);
  });
});

describe("POST /v1/convert", () => {
  it("converts plain text", async () => {
    const r = await post("/v1/convert", member, { text: colTxt, title: "X", artist: "Y" });
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).chordpro).toContain("{title: X}");
  });
  it("returns existing chordpro with title", async () => {
    const r = await post("/v1/convert", member, { text: amazing });
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).chordpro).toContain("{title:");
  });
  it("caps and validates", async () => {
    let r = await post("/v1/convert", member, { text: "x".repeat(200_001) });
    expect(r.status).toBe(413);
    r = await post("/v1/convert", member, { text: 5 });
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "invalid_input" });
    r = await post("/v1/convert", member, { text: "a".repeat(600_000) });
    expect(r.status).toBe(413);
  });
});

describe("POST /v1/songs/:id/report", () => {
  it("creates a row; hidden song is 404; long note 400", async () => {
    const s = (await (await post("/v1/songs", owner, { chordpro: amazing })).json()) as any;
    const r = await post(`/v1/songs/${s.id}/report`, member, { note: "wrong chord" });
    expect(r.status).toBe(204);
    const rows = await env.DB.prepare("SELECT * FROM reports").all<any>();
    expect(rows.results.length).toBe(1);
    expect(rows.results[0].song_id).toBe(s.id);
    expect(rows.results[0].user_id).toBe(member.id);
    expect(rows.results[0].note).toBe("wrong chord");

    const priv = (await (await post("/v1/songs", member, { chordpro: "{title: P}\n[C]x\n" })).json()) as any;
    expect((await post(`/v1/songs/${priv.id}/report`, other, { note: "n" })).status).toBe(404);
    expect((await post(`/v1/songs/nope/report`, other, { note: "n" })).status).toBe(404);
    expect((await post(`/v1/songs/${s.id}/report`, member, { note: "x".repeat(2001) })).status).toBe(400);
    expect((await post(`/v1/songs/${s.id}/report`, member, { note: 4 })).status).toBe(400);
    expect((await env.DB.prepare("SELECT COUNT(*) n FROM reports").first<any>()).n).toBe(1);
  });
});

describe("DoS bounds", () => {
  it("stream body without Content-Length over cap -> 413", async () => {
    const chunk = new TextEncoder().encode("x".repeat(64 * 1024));
    let sent = 0;
    const body = new ReadableStream({
      pull(ctl) {
        if (sent++ >= 20) return ctl.close();
        ctl.enqueue(chunk);
      },
    });
    // Call the app directly: aborting a real upload surfaces as a runtime-level
    // "network connection lost" in the test harness, unrelated to the handler.
    const req = new Request(ORIGIN + "/v1/convert", {
      method: "POST",
      headers: { ...member.headers, "content-type": "application/json" },
      body,
      // @ts-expect-error duplex needed for stream bodies
      duplex: "half",
    });
    const r = await app.fetch(req, env, { waitUntil() {}, passThroughOnException() {} } as any);
    expect(r.status).toBe(413);
    expect(await r.json()).toEqual({ error: "too_large" });
  });

  it("converts adversarial input quickly; output overflow -> 413", async () => {
    const text = ("C ".repeat(500) + "\nlyric line\n").repeat(Math.floor(200_000 / 512));
    const t0 = Date.now();
    const r = await post("/v1/convert", member, { text: text.slice(0, 200_000) });
    expect(Date.now() - t0).toBeLessThan(500);
    expect([200, 413]).toContain(r.status);
    // chord-only lines grow 1.5x ("C " -> "[C] "), so 200k of them overflows the output cap
    const over = await post("/v1/convert", member, { text: ("C ".repeat(64) + "\n").repeat(1_550).slice(0, 200_000) });
    expect(over.status).toBe(413);
    const big = "C\n".repeat(4_990) + "x\n";
    const r3 = await post("/v1/convert", member, { text: big });
    expect(r3.status).toBe(200);
  });

  it("blank language treated as default", async () => {
    const r = await post("/v1/songs", owner, { chordpro: amazing, language: "  " });
    expect(r.status).toBe(201);
    expect(((await r.json()) as any).language).toBe("en");
  });
});
