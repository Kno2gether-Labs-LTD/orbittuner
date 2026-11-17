import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env } from "../src/env";

vi.mock("../src/ai/llm", async (orig) => ({ ...(await orig<typeof import("../src/ai/llm")>()), complete: vi.fn() }));
vi.mock("../src/ai/fetchPage", async (orig) => ({
  ...(await orig<typeof import("../src/ai/fetchPage")>()),
  fetchPage: vi.fn(),
}));
import { complete } from "../src/ai/llm";
import { fetchPage } from "../src/ai/fetchPage";
import worker from "../src/index";
import { importStatus } from "../src/ai/jobs";
import { setSongStatus, upsertSong } from "../src/songs/repo";
import { clearSongs, makeUser, ORIGIN } from "./helpers";
import amazing from "./fixtures/chordpro/amazing-grace.cho?raw";
import backfill from "../migrations/0010_moderation_hardening.sql?raw";

const llm = vi.mocked(complete);
const page = vi.mocked(fetchPage);
const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;
const testEnv = () => env as unknown as Env;

type Who = Awaited<ReturnType<typeof makeUser>>;
let owner: Who, admin: Who, member: Who, other: Who, third: Who, fourth: Who, anon: Who;

/** Four distinct chords and four lyric lines. */
const GOOD = "{title: Good Song}\n{artist: Someone}\n[G]one line [C]here\n[D]two line here\n[Em]three line here\nfour line here\n";
/** Only three distinct chords. */
const FEW_CHORDS = "{title: Few Chords}\n[G]one line [C]here\n[D]two line here\nthree line here\nfour line here\n";
/** Four chords but three lyric lines. */
const FEW_LINES = "{title: Few Lines}\n[G]one line [C]here\n[D]two line here\n[Em]three line here\n";

/** Source page holding every chord the AI replies use (the provenance check rejects invented chords). */
const SRC = "G      C\none line here\nD\ntwo line here\nEm\nthree line here\nfour line here\n";
const aiReply = (title: string, confidence: number, chordpro = GOOD.replace("Good Song", title)) => ({
  text: JSON.stringify({ chordpro, language: "en", categories: ["hymn"], key: "G", confidence }),
  providerId: "p1", model: "m1", ms: 3,
});

function call(method: string, path: string, who: { headers: Record<string, string> }, body?: unknown, ip = "203.0.113.9") {
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
const flag = (who: Who, id: string, body: unknown = { reason: "wrong_chords" }, ip?: string) =>
  call("POST", `/v1/songs/${id}/report`, who, body, ip);
const status = async (id: string) =>
  (await env.DB.prepare("SELECT status FROM songs WHERE id = ?").bind(id).first<{ status: string }>())!.status;

async function create(who: Who, chordpro: string) {
  const r = await call("POST", "/v1/songs", who, { chordpro });
  expect(r.status).toBe(201);
  return (await r.json()) as any;
}
/** A published song created by `who`, straight into the DB. */
const seed = async (who: Who, title: string) =>
  upsertSong(env.DB, { chordpro: GOOD.replace("Good Song", title), source: "owner", status: "published", createdBy: who.id });

const U = (role: "owner" | "admin" | "user", linked: boolean) => ({ role, linked });

beforeEach(async () => {
  llm.mockReset();
  page.mockReset();
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM reports"), env.DB.prepare("DELETE FROM song_flags"),
    env.DB.prepare("DELETE FROM user_ai_usage"), env.DB.prepare("DELETE FROM ai_request_attempts"),
    env.DB.prepare("UPDATE settings SET value = '3' WHERE key = 'flag_hide_threshold'"),
  ]);
  owner = await makeUser("owner", "o");
  admin = await makeUser("admin", "a");
  member = await makeUser("user", "m", { linked: true });
  other = await makeUser("user", "x", { linked: true });
  third = await makeUser("user", "y", { linked: true });
  fourth = await makeUser("user", "z", { linked: true });
  anon = await makeUser("user", "anon");
});

describe("publish rules", () => {
  it("importStatus: staff always publish; anonymous users never; linked users by confidence AND validation", () => {
    expect(importStatus(U("owner", false), 0.1)).toBe("published");
    expect(importStatus(U("admin", false), undefined)).toBe("published");
    // Anonymous (no Google identity): review, whatever the content.
    expect(importStatus(U("user", false), 0.99, GOOD)).toBe("review");
    expect(importStatus(U("user", false), undefined, GOOD)).toBe("review");
    // Linked, AI: confidence >= 0.8 and the deterministic rule.
    expect(importStatus(U("user", true), 0.8, GOOD)).toBe("published");
    expect(importStatus(U("user", true), 0.79, GOOD)).toBe("review");
    expect(importStatus(U("user", true), 0.99, FEW_CHORDS)).toBe("review");
    expect(importStatus(U("user", true), 0.99)).toBe("review"); // no chordpro to judge
    // Linked, hand-written: the deterministic rule.
    expect(importStatus(U("user", true), undefined, GOOD)).toBe("published");
    expect(importStatus(U("user", true), undefined, FEW_CHORDS)).toBe("review");
    expect(importStatus(U("user", true), undefined, FEW_LINES)).toBe("review");
    expect(importStatus(U("user", true), undefined, "[G]a\n[C]b\n[D]c\n[Em]d\n")).toBe("review"); // no title
    expect(importStatus(U("user", true), undefined)).toBe("review");
  });

  it("a linked user's AI link import publishes at confidence >= 0.8 with a valid song, else review", async () => {
    page.mockResolvedValue({ text: SRC, via: "direct", finalUrl: "https://example.com/" });
    llm.mockResolvedValueOnce(aiReply("Confident", 0.8));
    let r = await call("POST", "/v1/songs", member, { url: "https://example.com/a" });
    expect(r.status).toBe(201);
    expect(((await r.json()) as any).status).toBe("published");
    llm.mockResolvedValueOnce(aiReply("Unsure", 0.79));
    r = await call("POST", "/v1/songs", other, { url: "https://example.com/b" });
    expect(((await r.json()) as any).status).toBe("review");
    llm.mockResolvedValueOnce(aiReply("Thin", 0.99, "{title: Thin}\n[G]Amazing [C]grace\n"));
    r = await call("POST", "/v1/songs", third, { url: "https://example.com/c" });
    expect(((await r.json()) as any).status).toBe("review");
  });

  it("an anonymous user's confident AI import and valid hand-written song both go to review", async () => {
    page.mockResolvedValue({ text: SRC, via: "direct", finalUrl: "https://example.com/" });
    llm.mockResolvedValueOnce(aiReply("Anon AI", 0.99));
    const r = await call("POST", "/v1/songs", anon, { url: "https://example.com/n" });
    expect(((await r.json()) as any).status).toBe("review");
    expect((await create(anon, GOOD)).status).toBe("review");
  });

  it("an owner's low-confidence AI result is published", async () => {
    page.mockResolvedValue({ text: SRC, via: "direct", finalUrl: "https://example.com/" });
    llm.mockResolvedValueOnce(aiReply("Owner Low", 0.3));
    const r = await call("POST", "/v1/songs", owner, { url: "https://example.com/o" });
    expect(((await r.json()) as any).status).toBe("published");
  });

  it("a linked user's hand-written ChordPro publishes only when it validates", async () => {
    expect((await create(member, GOOD)).status).toBe("published");
    expect((await create(member, FEW_CHORDS)).status).toBe("review");
    expect((await create(member, FEW_LINES)).status).toBe("review");
  });

  it("a visible duplicate still answers 409 with the existing id", async () => {
    const first = await create(member, GOOD);
    const r = await call("POST", "/v1/songs", other, { chordpro: GOOD });
    expect(r.status).toBe(409);
    expect(await r.json()).toEqual({ error: "duplicate", songId: first.id });
  });

  it("a copy of a hidden song goes to review (201, no id revealed)", async () => {
    const s = await create(member, GOOD);
    await setSongStatus(env.DB, s.id, "hidden", admin.id);
    const r = await call("POST", "/v1/songs", other, { chordpro: GOOD });
    expect(r.status).toBe(201);
    const body = (await r.json()) as any;
    expect(body.status).toBe("review");
    expect(JSON.stringify(body)).not.toContain(s.id);
  });

  it("a copy of a moderated song another user cannot see goes to review", async () => {
    const s = await create(member, GOOD);
    await setSongStatus(env.DB, s.id, "review", admin.id); // admin sent it back to review
    const r = await call("POST", "/v1/songs", other, { chordpro: GOOD });
    expect(r.status).toBe(201);
    expect(((await r.json()) as any).status).toBe("review");
  });
});

describe("addedBy", () => {
  it("is OrbitTuner for owner/admin songs and member otherwise, on Song and SongSummary, with no ids", async () => {
    const o = await create(owner, amazing);
    const a = await create(admin, GOOD.replace("Good Song", "Admin Song"));
    const m = await create(member, GOOD.replace("Good Song", "Member Song"));
    expect(o.addedBy).toBe("OrbitTuner");
    expect(a.addedBy).toBe("OrbitTuner");
    expect(m.addedBy).toBe("member");
    const one = (await (await call("GET", `/v1/songs/${m.id}`, other)).json()) as any;
    expect(one.addedBy).toBe("member");
    expect(JSON.stringify(one)).not.toContain(member.id);
    const browse = (await (await call("GET", "/v1/browse", other)).json()) as any;
    const by = Object.fromEntries(browse.items.map((s: any) => [s.id, s.addedBy]));
    expect(by).toEqual({ [o.id]: "OrbitTuner", [a.id]: "OrbitTuner", [m.id]: "member" });
    const search = (await (await call("GET", "/v1/search?q=member", other)).json()) as any;
    expect(search.items.map((s: any) => s.addedBy)).toEqual(["member"]);
    const changes = (await (await call("GET", `/v1/songs/changes?since=0&ids=${o.id}`, other)).json()) as any;
    expect(changes.items[0].addedBy).toBe("OrbitTuner");
    const all = JSON.stringify([browse, search, changes]);
    for (const u of [owner, admin, member]) expect(all).not.toContain(u.id);
  });
});

describe("flags", () => {
  it("validates reason and note, refuses the creator, and 404s an invisible song", async () => {
    const s = await create(member, GOOD);
    expect((await flag(other, s.id, { reason: "spam" })).status).toBe(400);
    expect((await flag(other, s.id, { reason: 5 })).status).toBe(400);
    expect((await flag(other, s.id, { reason: "other", note: "x".repeat(501) })).status).toBe(400);
    const own = await flag(member, s.id);
    expect(own.status).toBe(400);
    expect(await own.json()).toEqual({ error: "own_song" });
    const hidden = await create(member, FEW_CHORDS); // review: invisible to others
    expect((await flag(other, hidden.id)).status).toBe(404);
    expect((await flag(other, "nope")).status).toBe(404);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM song_flags").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("is idempotent per user (one flag, one report row) and defaults the reason to other", async () => {
    const s = await create(member, GOOD);
    expect((await flag(other, s.id, { note: "verse 2 chord" })).status).toBe(204);
    expect((await flag(other, s.id, { reason: "wrong_lyrics" })).status).toBe(204);
    const rows = await env.DB.prepare("SELECT user_id, reason, note FROM song_flags").all<any>();
    expect(rows.results).toEqual([{ user_id: other.id, reason: "other", note: "verse 2 chord" }]);
    const reports = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports").first<{ n: number }>();
    expect(reports!.n).toBe(1);
    expect(await status(s.id)).toBe("published");
  });

  it("a repeat flag in the same millisecond writes no second report row", async () => {
    const s = await create(member, GOOD);
    const now = vi.spyOn(Date, "now").mockReturnValue(Date.now());
    try {
      expect((await flag(other, s.id)).status).toBe(204);
      expect((await flag(other, s.id)).status).toBe(204);
    } finally {
      now.mockRestore();
    }
    const reports = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports").first<{ n: number }>();
    expect(reports!.n).toBe(1);
  });

  it("the 3rd distinct linked flag hides the song in the same write; repeats from one user never do", async () => {
    const s = await create(member, GOOD);
    for (let i = 0; i < 3; i++) expect((await flag(other, s.id)).status).toBe(204);
    expect((await flag(third, s.id)).status).toBe(204);
    expect(await status(s.id)).toBe("published");
    const before = (await env.DB.prepare("SELECT version FROM songs WHERE id = ?").bind(s.id).first<any>()).version;
    expect((await flag(fourth, s.id)).status).toBe(204);
    const row = await env.DB.prepare("SELECT status, version FROM songs WHERE id = ?").bind(s.id).first<any>();
    expect(row).toEqual({ status: "hidden", version: before + 1 });
  });

  it("a repeat flag never triggers the hide, even after the threshold drops", async () => {
    const s = await create(member, GOOD);
    expect((await flag(other, s.id)).status).toBe(204);
    await env.DB.prepare("UPDATE settings SET value = '1' WHERE key = 'flag_hide_threshold'").run();
    expect((await flag(other, s.id)).status).toBe(204);
    expect(await status(s.id)).toBe("published");
  });

  it("flags from anonymous accounts are stored but never count toward hiding", async () => {
    const s = await create(member, GOOD);
    const anons = [anon, await makeUser("user", "anon2"), await makeUser("user", "anon3"), await makeUser("user", "anon4")];
    for (const u of anons) expect((await flag(u, s.id)).status).toBe(204);
    expect(await status(s.id)).toBe("published");
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM song_flags").first<{ n: number }>();
    expect(n!.n).toBe(4);
    await flag(other, s.id);
    await flag(third, s.id);
    expect(await status(s.id)).toBe("published");
    await flag(fourth, s.id);
    expect(await status(s.id)).toBe("hidden");
  });

  it("an owner/admin-created song is never auto-hidden", async () => {
    const o = await create(owner, amazing);
    const a = await create(admin, GOOD.replace("Good Song", "Admin Song"));
    for (const u of [other, third, fourth]) {
      await flag(u, o.id);
      await flag(u, a.id);
    }
    expect(await status(o.id)).toBe("published");
    expect(await status(a.id)).toBe("published");
  });

  it("concurrent flags reaching the threshold still hide exactly once", async () => {
    const s = await create(member, GOOD);
    expect((await flag(other, s.id)).status).toBe(204);
    const rs = await Promise.all([flag(third, s.id), flag(fourth, s.id)]);
    expect(rs.map((r) => r.status)).toEqual([204, 204]);
    expect(await status(s.id)).toBe("hidden");
  });

  it("uses the flag_hide_threshold setting", async () => {
    await env.DB.prepare("UPDATE settings SET value = '2' WHERE key = 'flag_hide_threshold'").run();
    const s = await create(member, GOOD);
    await flag(other, s.id);
    await flag(third, s.id);
    expect(await status(s.id)).toBe("hidden");
  });

  it("a hidden song disappears for other users but stays for admins and its creator", async () => {
    const s = await create(member, GOOD);
    for (const u of [other, third, fourth]) await flag(u, s.id);
    expect(await status(s.id)).toBe("hidden");
    expect((await call("GET", `/v1/songs/${s.id}`, other)).status).toBe(404);
    const browse = (await (await call("GET", "/v1/browse", other)).json()) as any;
    expect(browse.items).toEqual([]);
    const search = (await (await call("GET", "/v1/search?q=good", other)).json()) as any;
    expect(search.items).toEqual([]);
    const changes = (await (await call("GET", `/v1/songs/changes?since=0&ids=${s.id}`, other)).json()) as any;
    expect(changes.items).toEqual([]);
    for (const u of [admin, owner, member]) {
      const r = await call("GET", `/v1/songs/${s.id}`, u);
      expect(r.status).toBe(200);
      expect(((await r.json()) as any).status).toBe("hidden");
    }
  });

  it("an admin decision pins the song: flags no longer auto-hide it", async () => {
    const s = await create(member, GOOD);
    for (const u of [other, third, fourth]) await flag(u, s.id);
    expect(await status(s.id)).toBe("hidden");
    expect(await setSongStatus(env.DB, s.id, "published", admin.id)).toBe(true);
    const row = await env.DB.prepare("SELECT status, moderated_at, moderated_by FROM songs WHERE id = ?").bind(s.id).first<any>();
    expect(row.status).toBe("published");
    expect(row.moderated_by).toBe(admin.id);
    expect(row.moderated_at).toBeGreaterThan(0);
    const fifth = await makeUser("user", "w", { linked: true });
    expect((await flag(fifth, s.id)).status).toBe(204);
    expect(await status(s.id)).toBe("published");
  });

  it("publishing an already-published song still pins it", async () => {
    const s = await create(member, GOOD);
    await setSongStatus(env.DB, s.id, "published", admin.id);
    for (const u of [other, third, fourth]) await flag(u, s.id);
    expect(await status(s.id)).toBe("published");
  });
});

describe("flag rate limits", () => {
  it("a user gets 20 new flags per 24 h; the 21st is 429 flag_rate_limited; repeats stay 204", async () => {
    const songs = [];
    for (let i = 0; i < 21; i++) songs.push(await seed(member, `Song ${i}`));
    for (let i = 0; i < 20; i++) expect((await flag(other, songs[i].id)).status).toBe(204);
    const r = await flag(other, songs[20].id);
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "flag_rate_limited" });
    expect((await flag(other, songs[0].id)).status).toBe(204); // a repeat is still fine
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM song_flags WHERE user_id = ?").bind(other.id).first<{ n: number }>();
    expect(n!.n).toBe(20);
    // Flags older than 24 h no longer count.
    await env.DB.prepare("UPDATE song_flags SET created_at = created_at - 86400001 WHERE user_id = ?").bind(other.id).run();
    expect((await flag(other, songs[20].id)).status).toBe(204);
  });

  it("one IP gets 60 new flags per 24 h across accounts", async () => {
    const songs = [];
    for (let i = 0; i < 61; i++) songs.push(await seed(member, `Ip Song ${i}`));
    const users = [other, third, fourth];
    for (let i = 0; i < 60; i++) expect((await flag(users[Math.floor(i / 20)], songs[i].id, undefined, "198.51.100.7")).status).toBe(204);
    const fifth = await makeUser("user", "w", { linked: true });
    const r = await flag(fifth, songs[60].id, undefined, "198.51.100.7");
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "flag_rate_limited" });
    expect((await flag(fifth, songs[60].id, undefined, "198.51.100.8")).status).toBe(204);
  });
});

describe("migration 0010 backfill", () => {
  it("pins existing owner/admin songs and leaves member songs alone", async () => {
    const o = await seed(owner, "Old Owner");
    const a = await seed(admin, "Old Admin");
    const m = await seed(member, "Old Member");
    await env.DB.prepare("UPDATE songs SET moderated_at = NULL, moderated_by = NULL").run();
    const stmt = backfill.match(/UPDATE songs[\s\S]*?;/)![0];
    await env.DB.prepare(stmt).run();
    const rows = await env.DB.prepare("SELECT id, moderated_by, moderated_at FROM songs").all<any>();
    const by = Object.fromEntries(rows.results.map((r: any) => [r.id, r]));
    expect(by[o.id].moderated_by).toBe(owner.id);
    expect(by[a.id].moderated_by).toBe(admin.id);
    expect(by[o.id].moderated_at).toBeGreaterThan(0);
    expect(by[m.id].moderated_at).toBeNull();
  });
});
