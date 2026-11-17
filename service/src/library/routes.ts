import { Hono } from "hono";
import type { AppEnv } from "../auth/devices";
import { ApiError } from "../lib/errors";
import { readJsonBody as readJson } from "../lib/body";
import { randomToken } from "../lib/crypto";

const MAX_FAVOURITES = 1000;
const MAX_PLAYLIST_ITEMS = 500;
const MAX_NAME = 200;
const MAX_POSITION = 64;
const MAX_SONG_ID = 200;
const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/i;
const POSITION_RE = /^[0-9A-Za-z]+$/;

export type FavouriteItem = { songId: string; favourite: boolean; updatedAt: number };
export type ItemSettings = { transpose?: number; capo?: number; scrollSpeed?: number };
export type PlaylistItem = { songId: string; position: string; settings: ItemSettings };
export type Playlist = { id: string; name: string; deleted: boolean; updatedAt: number; modifiedAt: number; items: PlaylistItem[] };

export const libraryApp = new Hono<AppEnv>();

const bad = () => new ApiError(400, "invalid_input");

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const isTime = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0;
const isSongId = (v: unknown): v is string => typeof v === "string" && v.length >= 1 && v.length <= MAX_SONG_ID;

function parseSettings(v: unknown): ItemSettings {
  if (v === undefined || v === null) return {};
  if (!isObj(v)) throw bad();
  const out: ItemSettings = {};
  const { transpose, capo, scrollSpeed } = v;
  if (transpose !== undefined) {
    if (typeof transpose !== "number" || !Number.isInteger(transpose) || transpose < -11 || transpose > 11) throw bad();
    out.transpose = transpose;
  }
  if (capo !== undefined) {
    if (typeof capo !== "number" || !Number.isInteger(capo) || capo < 0 || capo > 11) throw bad();
    out.capo = capo;
  }
  if (scrollSpeed !== undefined) {
    if (typeof scrollSpeed !== "number" || !Number.isFinite(scrollSpeed) || scrollSpeed < 0 || scrollSpeed > 1000) throw bad();
    out.scrollSpeed = scrollSpeed;
  }
  return out;
}

// ---- favourites ----

async function listFavourites(db: D1Database, userId: string): Promise<FavouriteItem[]> {
  const { results } = await db
    .prepare("SELECT song_id, favourite, updated_at FROM favourites WHERE user_id = ? ORDER BY song_id")
    .bind(userId)
    .all<{ song_id: string; favourite: number; updated_at: number }>();
  return results.map((r) => ({ songId: r.song_id, favourite: r.favourite === 1, updatedAt: r.updated_at }));
}

libraryApp.get("/v1/me/favourites", async (c) =>
  c.json({ items: await listFavourites(c.env.DB, c.var.user.id) }),
);

libraryApp.put("/v1/me/favourites", async (c) => {
  const body = await readJson(c);
  const items = body.items;
  if (!Array.isArray(items) || items.length > MAX_FAVOURITES) throw bad();
  const parsed: FavouriteItem[] = items.map((it) => {
    if (!isObj(it) || !isSongId(it.songId) || typeof it.favourite !== "boolean" || !isTime(it.updatedAt)) throw bad();
    return { songId: it.songId, favourite: it.favourite, updatedAt: it.updatedAt };
  });
  const uid = c.var.user.id;
  // Dedupe in JS (newest wins; first seen wins ties), then a single statement.
  const best = new Map<string, FavouriteItem>();
  for (const it of parsed) {
    const prev = best.get(it.songId);
    if (!prev || it.updatedAt > prev.updatedAt) best.set(it.songId, it);
  }
  if (best.size) {
    const payload = JSON.stringify([...best.values()].map((it) => ({ ...it, favourite: it.favourite ? 1 : 0 })));
    await c.env.DB.prepare(
      `INSERT INTO favourites (user_id, song_id, favourite, updated_at)
       SELECT ?, json_extract(value, '$.songId'), json_extract(value, '$.favourite'), json_extract(value, '$.updatedAt')
         FROM json_each(?) WHERE true
       ON CONFLICT(user_id, song_id) DO UPDATE SET favourite = excluded.favourite, updated_at = excluded.updated_at
       WHERE excluded.updated_at > favourites.updated_at`,
    ).bind(uid, payload).run();
  }
  return c.json({ items: await listFavourites(c.env.DB, uid) });
});

// ---- playlists ----

type PlaylistRow = { id: string; name: string; deleted: number; updated_at: number; modified_at: number };
type ItemRow = { song_id: string | null; position: string | null; settings: string | null };

function toItem(r: ItemRow): PlaylistItem {
  let settings: ItemSettings = {};
  try {
    settings = parseSettings(JSON.parse(r.settings ?? "{}"));
  } catch {
    settings = {};
  }
  return { songId: r.song_id!, position: r.position!, settings };
}

async function loadPlaylists(db: D1Database, userId: string, since: number | null, id?: string): Promise<Playlist[]> {
  const where = id ? "p.user_id = ? AND p.id = ?" : "p.user_id = ? AND p.modified_at > ?";
  const { results } = await db
    .prepare(
      `SELECT p.id, p.name, p.deleted, p.updated_at, p.modified_at, i.song_id, i.position, i.settings
         FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
        WHERE ${where}
        ORDER BY p.modified_at, p.id, i.position, i.song_id`,
    )
    .bind(userId, id ?? since)
    .all<PlaylistRow & ItemRow>();
  const out: Playlist[] = [];
  for (const r of results) {
    let p = out[out.length - 1];
    if (!p || p.id !== r.id) {
      p = { id: r.id, name: r.name, deleted: r.deleted === 1, updatedAt: r.updated_at, modifiedAt: r.modified_at, items: [] };
      out.push(p);
    }
    if (r.song_id != null) p.items.push(toItem(r));
  }
  return out;
}

libraryApp.get("/v1/me/playlists", async (c) => {
  const since = Number(c.req.query("since") ?? "0");
  if (!Number.isFinite(since) || since < 0) throw new ApiError(400, "invalid_since");
  const items = await loadPlaylists(c.env.DB, c.var.user.id, since);
  // Server-stamped cursor: clients store it and send it back as ?since=.
  const cursor = items.reduce((m, p) => Math.max(m, p.modifiedAt), since);
  return c.json({ items, cursor });
});

libraryApp.put("/v1/me/playlists/:id", async (c) => {
  const id = c.req.param("id");
  if (!ULID_RE.test(id)) throw bad();
  const body = await readJson(c);
  const { name, deleted, updatedAt, items } = body;
  if (typeof name !== "string" || name.length < 1 || name.length > MAX_NAME) throw bad();
  if (typeof deleted !== "boolean" || !isTime(updatedAt)) throw bad();
  if (!Array.isArray(items) || items.length > MAX_PLAYLIST_ITEMS) throw bad();
  const seen = new Set<string>();
  const parsed: PlaylistItem[] = items.map((it) => {
    if (!isObj(it) || !isSongId(it.songId)) throw bad();
    const pos = it.position;
    if (typeof pos !== "string" || pos.length < 1 || pos.length > MAX_POSITION || !POSITION_RE.test(pos)) throw bad();
    if (seen.has(it.songId)) throw bad(); // (playlist_id, song_id) is the primary key
    seen.add(it.songId);
    return { songId: it.songId, position: pos, settings: parseSettings(it.settings) };
  });

  const uid = c.var.user.id;
  const db = c.env.DB;
  const existing = await db
    .prepare("SELECT user_id, updated_at FROM playlists WHERE id = ?")
    .bind(id)
    .first<{ user_id: string; updated_at: number }>();
  if (existing && existing.user_id !== uid) throw new ApiError(404, "not_found");
  const stale = async () => {
    const [current] = await loadPlaylists(db, uid, null, id);
    if (!current) throw new ApiError(404, "not_found");
    return c.json({ error: "stale", current }, 409);
  };
  if (existing && existing.updated_at >= updatedAt) return stale();

  // Atomic, 3 statements. The row upsert is guarded by owner + strictly newer
  // and stamps a per-request token; items only change when that token is ours.
  const token = randomToken(16);
  const guard = "EXISTS (SELECT 1 FROM playlists WHERE id = ? AND user_id = ? AND write_token = ?)";
  const res = await db.batch([
    db
      .prepare(
        `INSERT INTO playlists (id, user_id, name, deleted, updated_at, write_token, modified_at)
         VALUES (?, ?, ?, ?, ?, ?, max(?, COALESCE((SELECT MAX(modified_at) FROM playlists WHERE user_id = ?), 0) + 1))
         ON CONFLICT(id) DO UPDATE SET name = excluded.name, deleted = excluded.deleted,
           updated_at = excluded.updated_at, write_token = excluded.write_token,
           modified_at = excluded.modified_at
         WHERE playlists.user_id = excluded.user_id AND playlists.updated_at < excluded.updated_at`,
      )
      .bind(id, uid, name, deleted ? 1 : 0, updatedAt, token, Date.now(), uid),
    db.prepare(`DELETE FROM playlist_items WHERE playlist_id = ? AND ${guard}`).bind(id, id, uid, token),
    db
      .prepare(
        `INSERT INTO playlist_items (playlist_id, song_id, position, settings)
         SELECT ?, json_extract(value, '$.songId'), json_extract(value, '$.position'), json_extract(value, '$.settings')
           FROM json_each(?) WHERE ${guard}`,
      )
      .bind(id, JSON.stringify(parsed), id, uid, token),
  ]);
  if (!res[0].meta.changes) return stale();
  const [saved] = await loadPlaylists(db, uid, null, id);
  return c.json(saved);
});
