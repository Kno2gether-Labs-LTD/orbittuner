import { env, SELF } from "cloudflare:test";
import { strToU8, unzipSync, zipSync } from "fflate";
import { beforeEach, describe, expect, it } from "vitest";
import { clearSongs, makeUser, ORIGIN } from "./helpers";
import { exportJson, exportZip, importFiles } from "../src/transfer/zip";
import { upsertSong } from "../src/songs/repo";
import amazing from "./fixtures/chordpro/amazing-grace.cho?raw";
import unknownDir from "./fixtures/chordpro/unknown-directives.cho?raw";
import unicode from "./fixtures/chordpro/unicode.cho?raw";
import noTitle from "./fixtures/chordpro/no-title.cho?raw";

const get = (path: string, who: { headers: Record<string, string> }) =>
  SELF.fetch(ORIGIN + path, { headers: who.headers });

let owner: Awaited<ReturnType<typeof makeUser>>;
let member: Awaited<ReturnType<typeof makeUser>>;
let other: Awaited<ReturnType<typeof makeUser>>;

beforeEach(async () => {
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM playlists"), env.DB.prepare("DELETE FROM playlist_items"),
    env.DB.prepare("DELETE FROM favourites"),
  ]);
  owner = await makeUser("owner", "o");
  member = await makeUser("user", "m");
  other = await makeUser("user", "x");
});

const u = (s: string) => strToU8(s);
const zipOf = (files: Record<string, string>) =>
  zipSync(Object.fromEntries(Object.entries(files).map(([k, v]) => [k, u(v)])));
const ow = () => ({ id: owner.id, name: owner.name, role: "owner" as const, linked: false });
const mem = () => ({ id: member.id, name: member.name, role: "user" as const, linked: false });
const oth = () => ({ id: other.id, name: other.name, role: "user" as const, linked: false });
const rows = async () =>
  (await env.DB.prepare("SELECT chordpro, status, source, created_by FROM songs ORDER BY title").all<{
    chordpro: string; status: string; source: string; created_by: string;
  }>()).results;

describe("import/export round trip", () => {
  it("round-trips chordpro byte-equal incl. unicode", async () => {
    const zip = zipOf({ "a.cho": amazing, "b.cho": unknownDir, "c.cho": unicode });
    const r = await importFiles(env.DB, ow(), [{ name: "songs.zip", data: zip }]);
    expect(r).toEqual({ added: 3, duplicates: [], failed: [] });
    const out = await exportZip(env.DB, ow());
    const files = unzipSync(out);
    const manifest = JSON.parse(new TextDecoder().decode(files["manifest.json"]));
    expect(manifest).toHaveLength(3);
    expect(Object.keys(manifest[0]).sort()).toEqual(["categories", "file", "id", "language", "status"]);
    await clearSongs();
    const r2 = await importFiles(env.DB, ow(), [{ name: "backup.zip", data: out }]);
    expect(r2.added).toBe(3);
    const got = (await rows()).map((x) => x.chordpro).sort();
    expect(got).toEqual([amazing, unknownDir, unicode].sort());
    expect((await rows()).every((x) => x.source === "import" && x.status === "published")).toBe(true);
  });

  it("manifest language/categories survive the round trip", async () => {
    await upsertSong(env.DB, {
      chordpro: unicode, language: "hi", categories: ["worship", "fast"], source: "owner",
      status: "published", createdBy: owner.id,
    });
    const out = await exportZip(env.DB, ow());
    await clearSongs();
    await importFiles(env.DB, ow(), [{ name: "x.zip", data: out }]);
    const r = await env.DB.prepare("SELECT language, categories FROM songs").first<{ language: string; categories: string }>();
    expect(r!.language).toBe("hi");
    expect(JSON.parse(r!.categories)).toEqual(["worship", "fast"]);
  });

  it("strips BOM and decodes CRLF/invalid bytes without throwing", async () => {
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...u("{title: Bom Song}\r\n[C]Hi "), 0xff, ...u("x\r\n")]);
    const r = await importFiles(env.DB, ow(), [{ name: "bom.cho", data: bytes }]);
    expect(r.added).toBe(1);
    const row = (await rows())[0];
    expect(row.chordpro.charCodeAt(0)).not.toBe(0xfeff);
    expect(row.chordpro.startsWith("{title: Bom Song}")).toBe(true);
  });
});

describe("import behaviour", () => {
  it("reports missing_title but adds the others", async () => {
    const zip = zipOf({ "a.cho": amazing, "no-title.cho": noTitle });
    const r = await importFiles(env.DB, ow(), [{ name: "s.zip", data: zip }]);
    expect(r.added).toBe(1);
    expect(r.failed).toEqual([{ name: "no-title.cho", error: "missing_title" }]);
  });

  it("converts plain .txt using filename for title and artist", async () => {
    const r = await importFiles(env.DB, ow(), [
      { name: "My Song - Artist.txt", data: u("   C        G\nHello darkness my friend\n") },
    ]);
    expect(r.added).toBe(1);
    const s = await env.DB.prepare("SELECT title, artist FROM songs").first<{ title: string; artist: string }>();
    expect(s).toEqual({ title: "My Song", artist: "Artist" });
  });

  it("lists duplicates instead of adding again", async () => {
    await importFiles(env.DB, ow(), [{ name: "a.cho", data: u(amazing) }]);
    const r = await importFiles(env.DB, ow(), [
      { name: "again.cho", data: u(amazing) },
      { name: "u.cho", data: u(unicode) },
    ]);
    expect(r).toEqual({ added: 1, duplicates: ["again.cho"], failed: [] });
    expect((await rows()).length).toBe(2);
  });

  it("member imports land in review", async () => {
    await importFiles(env.DB, mem(), [{ name: "a.cho", data: u(amazing) }]);
    const row = (await rows())[0];
    expect(row.status).toBe("review");
    expect(row.created_by).toBe(member.id);
  });

  it("imports a JSON backup and ignores unsupported/junk files", async () => {
    const backup = JSON.stringify({ version: 1, songs: [{ chordpro: amazing, language: "en", categories: ["hymn"] }, { chordpro: 5 }], playlists: [] });
    const zip = zipOf({ "__MACOSX/._a.cho": "junk", "readme.pdf": "x", "backup.json": backup });
    const r = await importFiles(env.DB, ow(), [{ name: "z.zip", data: zip }]);
    expect(r.added).toBe(1);
    expect(r.failed.map((f) => f.name).sort()).toEqual(["backup.json#2", "readme.pdf"]);
  });

  it("handles one nested zip level, rejects deeper", async () => {
    const inner = zipSync({ "a.cho": u(amazing) });
    const deeper = zipSync({ "in.zip": zipSync({ "b.cho": u(unicode) }) });
    const ok = await importFiles(env.DB, ow(), [{ name: "o.zip", data: zipSync({ "in.zip": inner }) }]);
    expect(ok.added).toBe(1);
    const bad = await importFiles(env.DB, ow(), [{ name: "o2.zip", data: zipSync({ "d.zip": deeper }) }]);
    expect(bad.added).toBe(0);
    expect(bad.failed).toEqual([{ name: "in.zip", error: "nested_too_deep" }]);
  });

  it("rejects traversal names and corrupt zips without crashing", async () => {
    const zip = zipSync({ "../../etc/x.cho": u(amazing), "ok/u.cho": u(unicode) });
    const r = await importFiles(env.DB, ow(), [{ name: "t.zip", data: zip }, { name: "bad.zip", data: u("not a zip") }]);
    expect(r.added).toBe(1);
    expect(r.failed.map((f) => f.error).sort()).toEqual(["invalid_name", "invalid_zip"]);
  });

  it("zip bomb: too large overall becomes a failed entry", async () => {
    const big = new Uint8Array(900_000).fill(97);
    const entries: Record<string, Uint8Array> = {};
    for (let i = 0; i < 30; i++) entries[`f${i}.txt`] = big; // 27 MB > 20 MB cap
    const r = await importFiles(env.DB, ow(), [{ name: "bomb.zip", data: zipSync(entries, { level: 9 }) }]);
    expect(r.failed.some((f) => f.name === "bomb.zip" && f.error === "archive_too_large")).toBe(true);
  });

  it("a single oversized entry fails with too_large, others still import", async () => {
    const zip = zipSync({ "huge.txt": new Uint8Array(1_500_000).fill(97), "a.cho": u(amazing) });
    const r = await importFiles(env.DB, ow(), [{ name: "z.zip", data: zip }]);
    expect(r.added).toBe(1);
    expect(r.failed).toEqual([{ name: "huge.txt", error: "too_large" }]);
  });

  it("a zip bomb aborts the whole import: nothing is written", async () => {
    const entries: Record<string, Uint8Array> = { "a.cho": u(amazing) };
    for (let i = 0; i < 30; i++) entries[`f${i}.txt`] = new Uint8Array(900_000).fill(97);
    const r = await importFiles(env.DB, ow(), [{ name: "bomb.zip", data: zipSync(entries, { level: 9 }) }]);
    expect(r.added).toBe(0);
    expect((await rows()).length).toBe(0);
  });

  it("JSON backup songs count against the shared entry budget", async () => {
    const songs = Array.from({ length: 2100 }, () => ({ chordpro: "x" }));
    const r = await importFiles(env.DB, ow(), [{ name: "b.json", data: u(JSON.stringify({ songs })) }]);
    expect(r.failed.some((f) => f.error === "archive_too_large")).toBe(true);
    expect(r.added).toBe(0);
  });

  it("too many entries fails the archive", async () => {
    const entries: Record<string, Uint8Array> = {};
    for (let i = 0; i < 2100; i++) entries[`e${i}.cho`] = u("x");
    const r = await importFiles(env.DB, ow(), [{ name: "many.zip", data: zipSync(entries) }]);
    expect(r.failed.some((f) => f.error === "archive_too_large")).toBe(true);
  });
});

function counted(db: D1Database) {
  let n = 0;
  const stmt = (st: any): any =>
    new Proxy(st, {
      get(t, k) {
        if (k === "__raw") return t;
        if (k === "bind") return (...a: unknown[]) => stmt(t.bind(...a));
        if (k === "first" || k === "all" || k === "run" || k === "raw") return (...a: unknown[]) => { n++; return t[k](...a); };
        const v = t[k];
        return typeof v === "function" ? v.bind(t) : v;
      },
    });
  const proxy = new Proxy(db as any, {
    get(t, k) {
      if (k === "prepare") return (q: string) => stmt(t.prepare(q));
      if (k === "batch") return (ss: any[]) => { n++; return t.batch(ss.map((x) => x.__raw ?? x)); };
      const v = t[k];
      return typeof v === "function" ? v.bind(t) : v;
    },
  });
  return { db: proxy as D1Database, calls: () => n };
}

const manyCho = (n: number) =>
  Object.fromEntries(Array.from({ length: n }, (_, i) => [`s${i}.cho`, `{title: Song ${i} x${i}}\n[C]La la ${i}\n`]));

describe("import cap and D1 call budget", () => {
  it("imports 200 songs in about 10 D1 calls or fewer", async () => {
    const c = counted(env.DB);
    const r = await importFiles(c.db, ow(), [{ name: "many.zip", data: zipOf(manyCho(200)) }]);
    expect(r).toEqual({ added: 200, duplicates: [], failed: [] });
    expect(c.calls()).toBeLessThanOrEqual(10);
    expect((await rows()).length).toBe(200);
    const fts = await env.DB.prepare("SELECT COUNT(*) AS n FROM songs_fts").first<{ n: number }>();
    expect(fts!.n).toBe(200);
  });

  it("201 songs -> too_many_songs and nothing written", async () => {
    await expect(importFiles(env.DB, ow(), [{ name: "many.zip", data: zipOf(manyCho(201)) }]))
      .rejects.toMatchObject({ status: 413, code: "too_many_songs", max: 200 });
    expect((await rows()).length).toBe(0);
  });

  it("cap comes from settings import_max_songs", async () => {
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('import_max_songs', '3')").run();
    try {
      await expect(importFiles(env.DB, ow(), [{ name: "m.zip", data: zipOf(manyCho(4)) }]))
        .rejects.toMatchObject({ code: "too_many_songs", max: 3 });
    } finally {
      await env.DB.prepare("DELETE FROM settings WHERE key = 'import_max_songs'").run();
    }
  });

  it("dedupes within one upload", async () => {
    const r = await importFiles(env.DB, ow(), [
      { name: "a.cho", data: u(amazing) }, { name: "b.cho", data: u(amazing) },
    ]);
    expect(r).toEqual({ added: 1, duplicates: ["b.cho"], failed: [] });
  });

  it("route answers 413 with max for 201 songs", async () => {
    const fd = new FormData();
    fd.append("files", new File([zipOf(manyCho(201))], "many.zip"));
    const res = await SELF.fetch(ORIGIN + "/v1/import", { method: "POST", headers: owner.headers, body: fd });
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ error: "too_many_songs", max: 200 });
    expect((await rows()).length).toBe(0);
  });
});

describe("export", () => {
  it("export without ids is capped at 2000 songs", async () => {
    const t = Date.now();
    const stmts = Array.from({ length: 2001 }, (_, i) =>
      env.DB.prepare(
        "INSERT INTO songs (id, title, chordpro, status, created_by, version, created_at, updated_at, dedupe_key) VALUES (?, ?, '{title: x}', 'published', ?, 1, ?, ?, ?)",
      ).bind(`id${i}`, `t${i}`, owner.id, t, t, `|t${i}`));
    for (let i = 0; i < stmts.length; i += 100) await env.DB.batch(stmts.slice(i, i + 100));
    await expect(exportZip(env.DB, ow())).rejects.toMatchObject({ status: 413, code: "too_many_songs" });
    expect((await get("/v1/export", owner)).status).toBe(413);
  });

  it("keeps the given songIds order and skips invisible songs", async () => {
    const mk = (chordpro: string, createdBy: string, status: "published" | "review") =>
      upsertSong(env.DB, { chordpro, source: "owner", status, createdBy });
    const a = await mk(amazing, owner.id, "published");
    const b = await mk(unicode, owner.id, "published");
    const hidden = await mk(unknownDir, owner.id, "review");
    const out = unzipSync(await exportZip(env.DB, oth(), [b.id, hidden.id, a.id, "nope", b.id]));
    const manifest = JSON.parse(new TextDecoder().decode(out["manifest.json"]));
    expect(manifest.map((m: { id: string }) => m.id)).toEqual([b.id, a.id]);
    expect(manifest[0].file).toMatch(/^songs\/cafe-senorita-[0-9a-z]+\.cho$/i);
    expect(new TextDecoder().decode(out[manifest[0].file])).toBe(unicode);
  });

  it("exportJson contains songs and owner library", async () => {
    const s = await upsertSong(env.DB, { chordpro: amazing, source: "owner", status: "published", createdBy: owner.id });
    await env.DB.batch([
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at) VALUES ('p1', ?, 'Sunday', 0, 1)").bind(owner.id),
      env.DB.prepare("INSERT INTO playlist_items (playlist_id, song_id, position, settings) VALUES ('p1', ?, 'a', '{\"capo\":2}')").bind(s.id),
    ]);
    const j = JSON.parse(await exportJson(env.DB));
    expect(j.version).toBe(1);
    expect(j.songs.map((x: { id: string }) => x.id)).toEqual([s.id]);
    expect(j.playlists[0]).toMatchObject({ id: "p1", name: "Sunday", items: [{ songId: s.id, position: "a", settings: { capo: 2 } }] });
    expect(j.users).toBeUndefined();
  });
});

describe("routes", () => {

  it("GET /v1/export returns a zip and honours ids", async () => {
    const a = await upsertSong(env.DB, { chordpro: amazing, source: "owner", status: "published", createdBy: owner.id });
    await upsertSong(env.DB, { chordpro: unicode, source: "owner", status: "published", createdBy: owner.id });
    const res = await get(`/v1/export?ids=${a.id}`, member);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/zip");
    const files = unzipSync(new Uint8Array(await res.arrayBuffer()));
    expect(JSON.parse(new TextDecoder().decode(files["manifest.json"]))).toHaveLength(1);
  });

  it("playlist export is in order and 404 for another user's playlist", async () => {
    const a = await upsertSong(env.DB, { chordpro: amazing, source: "owner", status: "published", createdBy: owner.id });
    const b = await upsertSong(env.DB, { chordpro: unicode, source: "owner", status: "published", createdBy: owner.id });
    await env.DB.batch([
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at) VALUES ('pl', ?, 'P', 0, 1)").bind(member.id),
      env.DB.prepare("INSERT INTO playlist_items (playlist_id, song_id, position, settings) VALUES ('pl', ?, 'b', '{}'), ('pl', ?, 'a', '{}')").bind(a.id, b.id),
    ]);
    const res = await get("/v1/playlists/pl/export", member);
    expect(res.status).toBe(200);
    const files = unzipSync(new Uint8Array(await res.arrayBuffer()));
    const m = JSON.parse(new TextDecoder().decode(files["manifest.json"]));
    expect(m.map((x: { id: string }) => x.id)).toEqual([b.id, a.id]);
    expect((await get("/v1/playlists/pl/export", other)).status).toBe(404);
    expect((await get("/v1/playlists/zzz/export", member)).status).toBe(404);
  });

  it("export/json is owner only", async () => {
    expect((await get("/v1/export/json", member)).status).toBe(403);
    expect((await get("/v1/export/json", owner)).status).toBe(200);
  });

  it("POST /v1/import takes multipart and 413s oversized bodies", async () => {
    const fd = new FormData();
    fd.append("files", new File([u(amazing)], "a.cho"));
    fd.append("files", new File([zipOf({ "u.cho": unicode })], "z.zip"));
    const res = await SELF.fetch(ORIGIN + "/v1/import", { method: "POST", headers: member.headers, body: fd });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ added: 2, duplicates: [], failed: [] });
    const big = await SELF.fetch(ORIGIN + "/v1/import", {
      method: "POST", headers: { ...member.headers, "content-type": "multipart/form-data; boundary=x", "content-length": String(60 * 1024 * 1024) },
      body: "x",
    });
    expect(big.status).toBe(413);
    const notMp = await SELF.fetch(ORIGIN + "/v1/import", { method: "POST", headers: { ...member.headers, "content-type": "text/plain" }, body: "hi" });
    expect(notMp.status).toBe(400);
  });

  it("POST /v1/import answers 413 for a zip bomb", async () => {
    const entries: Record<string, Uint8Array> = {};
    for (let i = 0; i < 30; i++) entries[`f${i}.txt`] = new Uint8Array(900_000).fill(97);
    const fd = new FormData();
    fd.append("files", new File([zipSync(entries, { level: 9 })], "bomb.zip"));
    const res = await SELF.fetch(ORIGIN + "/v1/import", { method: "POST", headers: owner.headers, body: fd });
    expect(res.status).toBe(413);
  });
});
