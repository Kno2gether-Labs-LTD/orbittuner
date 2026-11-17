import { validateSong } from "../chordpro/parse";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import type { AuthUser } from "../auth/devices";
import { isAdmin } from "../auth/roles";

export type SongSummary = {
  id: string;
  title: string;
  artist: string | null;
  language: string;
  categories: string[];
  originalKey: string | null;
  version: number;
  /**
   * "OrbitTuner" when the creator is currently the owner or an admin (it follows the creator's
   * current role: promoting or demoting a user changes it on their songs); never an id or a name.
   */
  addedBy: "member" | "OrbitTuner";
};
export type Song = SongSummary & {
  chordpro: string;
  hasNative: boolean;
  tempoBpm: number | null;
  timeSig: string | null;
  capo: number;
  difficulty: string | null;
  source: "owner" | "web" | "import";
  sourceUrl: string | null;
  status: "published" | "review" | "hidden";
  updatedAt: number;
};
export type Page<T> = { items: T[]; nextPage: number | null };
export type Facets = {
  languages: { code: string; count: number }[];
  categories: { name: string; count: number }[];
};

export const PAGE_SIZE = 25;
export const MAX_CHANGE_IDS = 200;

type Row = {
  id: string; title: string; artist: string | null; chordpro: string; chordpro_native: string | null;
  original_key: string | null; tempo_bpm: number | null; time_sig: string | null; capo: number | null;
  language: string | null; categories: string | null; difficulty: string | null;
  source: Song["source"]; source_url: string | null; status: Song["status"];
  version: number; updated_at: number;
  /** 1 when created_by is an owner/admin (see SONG_COLS). */
  by_staff: number | null;
};

/** Song columns (alias `s`) plus `by_staff`, for every query that maps rows through toSummary/toSong. */
const SONG_COLS =
  "s.*, (SELECT u.role IN ('owner','admin') FROM users u WHERE u.id = s.created_by) AS by_staff";

function parseCats(s: string | null): string[] {
  try {
    const v = JSON.parse(s ?? "[]");
    return Array.isArray(v) ? v.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

function toSummary(r: Row): SongSummary {
  return {
    id: r.id, title: r.title, artist: r.artist, language: r.language ?? "en",
    categories: parseCats(r.categories), originalKey: r.original_key, version: r.version,
    addedBy: r.by_staff ? "OrbitTuner" : "member",
  };
}

function toSong(r: Row): Song {
  return {
    ...toSummary(r),
    chordpro: r.chordpro,
    hasNative: r.chordpro_native != null,
    tempoBpm: r.tempo_bpm,
    timeSig: r.time_sig,
    capo: r.capo ?? 0,
    difficulty: r.difficulty,
    source: r.source,
    sourceUrl: r.source_url,
    status: r.status,
    updatedAt: r.updated_at,
  };
}

export function dedupeKey(artist: string | null, title: string): string {
  const norm = (s: string) =>
    s
      .normalize("NFKD")
      .replace(/\p{M}+/gu, "")
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\s]+/gu, "")
      .replace(/\s+/g, " ")
      .trim();
  return `${norm(artist ?? "")}|${norm(title)}`;
}

export type UpsertInput = {
  chordpro: string;
  language?: string;
  categories?: string[];
  difficulty?: string;
  source: Song["source"];
  sourceUrl?: string;
  status: Song["status"];
  createdBy: string;
  id?: string;
  /** Admin save of an existing song: pin it (moderated_at/by), so flags no longer auto-hide it. */
  moderatedBy?: string;
};

/** SQL condition (+ binds) that must hold for a guarded insert to write anything. */
export type InsertGuard = { sql: string; binds: unknown[] };

/**
 * Statements (songs + FTS) inserting a brand-new song; no reads. Throws ApiError on invalid chordpro.
 * With `guard`, both rows are written only when the guard holds at execution time (INSERT ... SELECT ... WHERE).
 */
export function prepareInsert(
  db: D1Database, input: UpsertInput, guard?: InsertGuard,
): { id: string; stmts: D1PreparedStatement[] } {
  const v = validateSong(input.chordpro);
  if (!v.ok) throw new ApiError(400, v.error);
  const { meta, lyricsText } = v.doc;
  const title = meta.title.trim();
  const artist = meta.artist?.trim() || null;
  const capoN = parseInt(meta.capo ?? "", 10);
  const tempoN = parseInt(meta.tempo ?? "", 10);
  const now = Date.now();
  const categories = [...new Set((input.categories ?? []).map((c) => c.trim()).filter(Boolean))];
  const id = input.id ?? ulid();
  const rows = (cols: string) => (guard ? `SELECT ${cols} WHERE ${guard.sql}` : `VALUES (${cols})`);
  const g = guard?.binds ?? [];
  return {
    id,
    stmts: [
      db.prepare(
        `INSERT INTO songs (id, title, artist, chordpro, chordpro_native, original_key, tempo_bpm, time_sig, capo,
           language, categories, difficulty, source, source_url, status, created_by, version, created_at,
           updated_at, dedupe_key)
         ${rows("?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?")}`,
      ).bind(
        id, title, artist, input.chordpro, meta.key?.trim() || null,
        Number.isFinite(tempoN) ? tempoN : null, meta.time?.trim() || null,
        Number.isFinite(capoN) ? capoN : 0, input.language ?? "en", JSON.stringify(categories),
        input.difficulty ?? null, input.source, input.sourceUrl ?? null, input.status, input.createdBy,
        now, now, dedupeKey(artist, title), ...g,
      ),
      db.prepare(
        `INSERT INTO songs_fts (song_id, title, artist, lyrics, categories) ${rows("?, ?, ?, ?, ?")}`,
      ).bind(id, title, artist ?? "", lyricsText, categories.join(" "), ...g),
    ],
  };
}

export async function upsertSong(db: D1Database, input: UpsertInput): Promise<Song> {
  const v = validateSong(input.chordpro);
  if (!v.ok) throw new ApiError(400, v.error);
  const { meta, lyricsText } = v.doc;
  const title = meta.title.trim();
  const artist = meta.artist?.trim() || null;
  const capoN = parseInt(meta.capo ?? "", 10);
  const tempoN = parseInt(meta.tempo ?? "", 10);
  const now = Date.now();

  const existing = input.id
    ? await db.prepare("SELECT * FROM songs WHERE id = ?").bind(input.id).first<Row>()
    : null;
  // On update, fields the caller omitted keep the stored values.
  const language = input.language ?? existing?.language ?? "en";
  const categories = input.categories
    ? [...new Set(input.categories.map((c) => c.trim()).filter(Boolean))]
    : existing
      ? parseCats(existing.categories)
      : [];
  const difficulty = input.difficulty ?? existing?.difficulty ?? null;
  const sourceUrl = input.sourceUrl ?? existing?.source_url ?? null;
  const id = existing?.id ?? input.id ?? ulid();

  const stmts: D1PreparedStatement[] = [];
  if (existing) {
    stmts.push(
      db.prepare(
        "INSERT INTO song_versions (id, song_id, chordpro, source_url, created_at) VALUES (?, ?, ?, ?, ?)",
      ).bind(ulid(), id, existing.chordpro, existing.source_url, now),
      db.prepare(
        `UPDATE songs SET title=?, artist=?, chordpro=?, original_key=?, tempo_bpm=?, time_sig=?, capo=?,
           language=?, categories=?, difficulty=?, source=?, source_url=?, status=?, version=version+1,
           updated_at=?, dedupe_key=? WHERE id=?`,
      ).bind(
        title, artist, input.chordpro, meta.key?.trim() || null, Number.isFinite(tempoN) ? tempoN : null,
        meta.time?.trim() || null, Number.isFinite(capoN) ? capoN : 0, language,
        JSON.stringify(categories), difficulty, input.source, sourceUrl,
        input.status, now, dedupeKey(artist, title), id,
      ),
    );
    if (input.moderatedBy !== undefined) {
      stmts.push(db.prepare("UPDATE songs SET moderated_at = ?, moderated_by = ? WHERE id = ?").bind(now, input.moderatedBy, id));
    }
  } else {
    const ins = prepareInsert(db, { ...input, language, categories, difficulty: difficulty ?? undefined, sourceUrl: sourceUrl ?? undefined, id });
    await db.batch(ins.stmts);
    const created = await db.prepare(`SELECT ${SONG_COLS} FROM songs s WHERE s.id = ?`).bind(id).first<Row>();
    return toSong(created!);
  }
  stmts.push(
    db.prepare("DELETE FROM songs_fts WHERE song_id = ?").bind(id),
    db.prepare(
      "INSERT INTO songs_fts (song_id, title, artist, lyrics, categories) VALUES (?, ?, ?, ?, ?)",
    ).bind(id, title, artist ?? "", lyricsText, categories.join(" ")),
  );
  await db.batch(stmts);
  const row = await db.prepare(`SELECT ${SONG_COLS} FROM songs s WHERE s.id = ?`).bind(id).first<Row>();
  return toSong(row!);
}

/** SQL fragment + binds restricting rows (alias `s`) to what the user may see. */
function visibility(user: AuthUser): { sql: string; binds: unknown[] } {
  if (isAdmin(user.role)) return { sql: "1=1", binds: [] };
  return { sql: "(s.status = 'published' OR s.created_by = ?)", binds: [user.id] };
}

/** Prefix-match expression from \p{L}\p{N} runs; null when nothing searchable. */
export function ftsQuery(q: string): string | null {
  const runs = q.match(/[\p{L}\p{N}]+/gu);
  if (!runs || runs.length === 0) return null;
  return runs.slice(0, 12).map((r) => `"${r}"*`).join(" AND ");
}

type Filters = { lang?: string; cat?: string };
function filterSql(f: Filters): { sql: string; binds: unknown[] } {
  const parts: string[] = [];
  const binds: unknown[] = [];
  if (f.lang) { parts.push("s.language = ?"); binds.push(f.lang); }
  if (f.cat) {
    parts.push("EXISTS (SELECT 1 FROM json_each(s.categories) j WHERE j.value = ?)");
    binds.push(f.cat);
  }
  return { sql: parts.map((p) => ` AND ${p}`).join(""), binds };
}

function toPage(rows: Row[], page: number): Page<SongSummary> {
  const more = rows.length > PAGE_SIZE;
  return { items: rows.slice(0, PAGE_SIZE).map(toSummary), nextPage: more ? page + 1 : null };
}

export async function searchSongs(
  db: D1Database,
  user: AuthUser,
  opts: { q: string; lang?: string; cat?: string; page?: number },
): Promise<Page<SongSummary>> {
  const page = Math.max(0, opts.page ?? 0);
  const match = ftsQuery(opts.q);
  if (!match) return { items: [], nextPage: null };
  const vis = visibility(user);
  const f = filterSql(opts);
  const { results } = await db
    .prepare(
      `SELECT ${SONG_COLS} FROM songs_fts JOIN songs s ON s.id = songs_fts.song_id
       WHERE songs_fts MATCH ? AND ${vis.sql}${f.sql}
       ORDER BY bm25(songs_fts, 0, 10.0, 6.0, 1.0, 2.0), s.id
       LIMIT ? OFFSET ?`,
    )
    .bind(match, ...vis.binds, ...f.binds, PAGE_SIZE + 1, page * PAGE_SIZE)
    .all<Row>();
  return toPage(results, page);
}

/**
 * Songs `user` may see (same visibility as searchSongs) matching a raw FTS5 expression, best
 * bm25 first. The caller builds `match` from sanitised tokens only (voice search).
 */
export async function ftsSummaries(db: D1Database, user: AuthUser, match: string, limit: number): Promise<SongSummary[]> {
  const vis = visibility(user);
  const { results } = await db
    .prepare(
      `SELECT ${SONG_COLS} FROM songs_fts JOIN songs s ON s.id = songs_fts.song_id
       WHERE songs_fts MATCH ? AND ${vis.sql}
       ORDER BY bm25(songs_fts, 0, 10.0, 6.0, 1.0, 2.0), s.id
       LIMIT ?`,
    )
    .bind(match, ...vis.binds, limit)
    .all<Row>();
  return results.map(toSummary);
}

export async function browseSongs(
  db: D1Database,
  user: AuthUser,
  opts: { lang?: string; cat?: string; page?: number },
): Promise<Page<SongSummary>> {
  const page = Math.max(0, opts.page ?? 0);
  const vis = visibility(user);
  const f = filterSql(opts);
  const { results } = await db
    .prepare(
      `SELECT ${SONG_COLS} FROM songs s WHERE ${vis.sql}${f.sql}
       ORDER BY s.updated_at DESC, s.id DESC LIMIT ? OFFSET ?`,
    )
    .bind(...vis.binds, ...f.binds, PAGE_SIZE + 1, page * PAGE_SIZE)
    .all<Row>();
  return toPage(results, page);
}

export async function getFacets(db: D1Database, user: AuthUser): Promise<Facets> {
  const vis = visibility(user);
  const [langs, cats] = await db.batch<{ k: string; n: number }>([
    db.prepare(
      `SELECT COALESCE(s.language, 'en') AS k, COUNT(*) AS n FROM songs s WHERE ${vis.sql}
       GROUP BY k ORDER BY n DESC, k`,
    ).bind(...vis.binds),
    db.prepare(
      `SELECT j.value AS k, COUNT(*) AS n FROM songs s, json_each(s.categories) j WHERE ${vis.sql}
       GROUP BY k ORDER BY n DESC, k`,
    ).bind(...vis.binds),
  ]);
  return {
    languages: langs.results.map((r) => ({ code: r.k, count: r.n })),
    categories: cats.results.map((r) => ({ name: r.k, count: r.n })),
  };
}

export async function getSong(db: D1Database, user: AuthUser, id: string): Promise<Song | null> {
  const vis = visibility(user);
  const row = await db
    .prepare(`SELECT ${SONG_COLS} FROM songs s WHERE s.id = ? AND ${vis.sql}`)
    .bind(id, ...vis.binds)
    .first<Row>();
  return row ? toSong(row) : null;
}

/** Id and creator of a song `user` may see, else null (the creator id stays server-side). */
export async function visibleSongCreator(
  db: D1Database, user: AuthUser, id: string,
): Promise<{ id: string; createdBy: string | null } | null> {
  const vis = visibility(user);
  const row = await db
    .prepare(`SELECT s.id, s.created_by FROM songs s WHERE s.id = ? AND ${vis.sql}`)
    .bind(id, ...vis.binds)
    .first<{ id: string; created_by: string | null }>();
  return row ? { id: row.id, createdBy: row.created_by } : null;
}

export async function songChanges(
  db: D1Database,
  user: AuthUser,
  since: number,
  ids: string[],
): Promise<Song[]> {
  if (ids.length === 0) return [];
  const vis = visibility(user);
  const { results } = await db
    .prepare(
      `SELECT ${SONG_COLS} FROM songs s WHERE s.id IN (SELECT value FROM json_each(?)) AND s.updated_at > ? AND ${vis.sql}
       ORDER BY s.updated_at, s.id`,
    )
    .bind(JSON.stringify(ids), since, ...vis.binds)
    .all<Row>();
  return results.map(toSong);
}

/** Id of an existing song with this dedupe key that `user` may see, else null. */
export async function findVisibleDuplicate(
  db: D1Database,
  user: AuthUser,
  artist: string | null,
  title: string,
): Promise<string | null> {
  const vis = visibility(user);
  const row = await db
    .prepare(`SELECT s.id FROM songs s WHERE s.dedupe_key = ? AND ${vis.sql} ORDER BY s.created_at, s.id LIMIT 1`)
    .bind(dedupeKey(artist, title), ...vis.binds)
    .first<{ id: string }>();
  return row?.id ?? null;
}

/**
 * Which of these dedupe keys belong to a song that is hidden or was moderated by an admin, in any
 * status and whoever created it. A non-admin contribution matching one goes to review (never 409,
 * so nothing about the existing song is revealed).
 */
export async function blockedDedupeKeys(db: D1Database, keys: string[]): Promise<Set<string>> {
  if (keys.length === 0) return new Set();
  const { results } = await db
    .prepare(
      `SELECT DISTINCT s.dedupe_key AS k FROM songs s
       WHERE s.dedupe_key IN (SELECT value FROM json_each(?)) AND (s.status = 'hidden' OR s.moderated_at IS NOT NULL)`,
    )
    .bind(JSON.stringify(keys))
    .all<{ k: string }>();
  return new Set(results.map((r) => r.k));
}

/** Every song `user` may see, ordered by title then id (used by export). */
export async function listVisibleSongs(db: D1Database, user: AuthUser, limit = -1): Promise<Song[]> {
  const vis = visibility(user);
  const { results } = await db
    .prepare(`SELECT ${SONG_COLS} FROM songs s WHERE ${vis.sql} ORDER BY s.title COLLATE NOCASE, s.id LIMIT ?`)
    .bind(...vis.binds, limit)
    .all<Row>();
  return results.map(toSong);
}

/** Which of these dedupe keys already exist on a song `user` may see (one query, keys bound as one JSON string). */
export async function visibleDedupeKeys(db: D1Database, user: AuthUser, keys: string[]): Promise<Set<string>> {
  if (keys.length === 0) return new Set();
  const vis = visibility(user);
  const { results } = await db
    .prepare(
      `SELECT DISTINCT s.dedupe_key AS k FROM songs s
       WHERE s.dedupe_key IN (SELECT value FROM json_each(?)) AND ${vis.sql}`,
    )
    .bind(JSON.stringify(keys), ...vis.binds)
    .all<{ k: string }>();
  return new Set(results.map((r) => r.k));
}

/** For each of these source URLs, the id of the oldest song `user` may see that was imported from it. */
export async function visibleSongsBySourceUrl(db: D1Database, user: AuthUser, urls: string[]): Promise<Map<string, string>> {
  if (urls.length === 0) return new Map();
  const vis = visibility(user);
  const { results } = await db
    .prepare(
      `SELECT s.source_url AS u, s.id FROM songs s
       WHERE s.source_url IN (SELECT value FROM json_each(?)) AND ${vis.sql}
       ORDER BY s.created_at DESC, s.id DESC`,
    )
    .bind(JSON.stringify(urls), ...vis.binds)
    .all<{ u: string; id: string }>();
  const out = new Map<string, string>();
  for (const r of results) out.set(r.u, r.id); // later (older) rows win
  return out;
}

/** Owner portal listing: optional FTS query, language, category and status filters, plus a total. */
export async function listSongsForOwner(
  db: D1Database,
  opts: { q?: string; lang?: string; cat?: string; status?: Song["status"]; page?: number },
): Promise<Page<Song> & { total: number }> {
  const page = Math.max(0, opts.page ?? 0);
  const f = filterSql(opts);
  const parts = [f.sql];
  const binds = [...f.binds];
  if (opts.status) { parts.push(" AND s.status = ?"); binds.push(opts.status); }
  const where = parts.join("");
  const match = opts.q ? ftsQuery(opts.q) : null;
  if (opts.q && !match) return { items: [], nextPage: null, total: 0 };
  const [rows, count] = match
    ? await db.batch([
        db.prepare(
          `SELECT ${SONG_COLS} FROM songs_fts JOIN songs s ON s.id = songs_fts.song_id
           WHERE songs_fts MATCH ?${where}
           ORDER BY bm25(songs_fts, 0, 10.0, 6.0, 1.0, 2.0), s.id LIMIT ? OFFSET ?`,
        ).bind(match, ...binds, PAGE_SIZE + 1, page * PAGE_SIZE),
        db.prepare(
          `SELECT COUNT(*) AS n FROM songs_fts JOIN songs s ON s.id = songs_fts.song_id
           WHERE songs_fts MATCH ?${where}`,
        ).bind(match, ...binds),
      ])
    : await db.batch([
        db.prepare(`SELECT ${SONG_COLS} FROM songs s WHERE 1=1${where} ORDER BY s.updated_at DESC, s.id DESC LIMIT ? OFFSET ?`)
          .bind(...binds, PAGE_SIZE + 1, page * PAGE_SIZE),
        db.prepare(`SELECT COUNT(*) AS n FROM songs s WHERE 1=1${where}`).bind(...binds),
      ]);
  const results = rows.results as Row[];
  return {
    items: results.slice(0, PAGE_SIZE).map(toSong),
    nextPage: results.length > PAGE_SIZE ? page + 1 : null,
    total: (count.results[0] as { n: number } | undefined)?.n ?? 0,
  };
}

/**
 * Set a song's status as a new version, so devices pick the change up on their next sync.
 * With `moderatorId` (an admin decision) the song is also pinned: moderated_at/by are stamped even
 * when the status is unchanged, and flags no longer auto-hide it. Returns whether the status changed.
 */
export async function setSongStatus(
  db: D1Database, id: string, status: Song["status"], moderatorId?: string,
): Promise<boolean> {
  const now = Date.now();
  const stmts = [
    db.prepare("UPDATE songs SET status = ?, version = version + 1, updated_at = ? WHERE id = ? AND status != ?")
      .bind(status, now, id, status),
  ];
  if (moderatorId !== undefined) {
    stmts.push(db.prepare("UPDATE songs SET moderated_at = ?, moderated_by = ? WHERE id = ?").bind(now, moderatorId, id));
  }
  const [r] = await db.batch(stmts);
  return r.meta.changes > 0;
}

export const FLAG_REASONS = ["wrong_chords", "wrong_lyrics", "not_a_song", "other"] as const;
export type FlagReason = (typeof FLAG_REASONS)[number];
const DEFAULT_FLAG_HIDE_THRESHOLD = 3;
/** New flags per user, and per client IP key, in any rolling 24 h. Repeat flags never count. */
export const FLAG_USER_DAILY = 20;
export const FLAG_IP_DAILY = 60;
const DAY_MS = 24 * 60 * 60_000;

/**
 * Record `userId`'s flag on a song. One batch, i.e. one transaction:
 *   1. Insert the flag only while the user has < 20 and the IP key < 60 flags in the last 24 h
 *      (strict: the counts are read inside the insert). A repeat flag is a no-op.
 *   2. Only when (1) added a row (marked by the flag's PK + this call's `created_at`): a
 *      back-compat `reports` row for the portal Review page (at most one per song and user, so a
 *      repeat flag landing in the same millisecond, which also matches the marker, writes none),
 *      and the auto-hide. The hide is count-conditional, so a same-millisecond repeat re-running
 *      it changes nothing. Auto-hide: a published song that no admin
 *      moderated and that no owner/admin created becomes hidden once its flags from
 *      Google-linked accounts reach settings.flag_hide_threshold. Anonymous flags are stored but
 *      never counted. Counts are read in the same transaction, so concurrent flags cannot race.
 * Returns "added", "repeat" (already flagged by this user) or "rate_limited".
 */
export async function flagSong(
  db: D1Database, songId: string, userId: string, ipKey: string, reason: FlagReason, note: string | null,
): Promise<"added" | "repeat" | "rate_limited"> {
  const now = Date.now();
  const since = now - DAY_MS;
  const reportNote = reason === "other" ? note : note ? `${reason}: ${note}` : reason;
  const mine = "EXISTS (SELECT 1 FROM song_flags WHERE song_id = ? AND user_id = ? AND created_at = ?)";
  const [ins] = await db.batch([
    db.prepare(
      `INSERT INTO song_flags (song_id, user_id, reason, note, created_at, ip_key)
       SELECT ?, ?, ?, ?, ?, ?
        WHERE (SELECT COUNT(*) FROM song_flags WHERE user_id = ? AND created_at > ?) < ?
          AND (SELECT COUNT(*) FROM song_flags WHERE ip_key = ? AND created_at > ?) < ?
       ON CONFLICT(song_id, user_id) DO NOTHING`,
    ).bind(songId, userId, reason, note, now, ipKey, userId, since, FLAG_USER_DAILY, ipKey, since, FLAG_IP_DAILY),
    db.prepare(
      `INSERT INTO reports (id, song_id, user_id, note, created_at, resolved_at)
       SELECT ?, ?, ?, ?, ?, NULL
        WHERE ${mine} AND NOT EXISTS (SELECT 1 FROM reports WHERE song_id = ? AND user_id = ?)`,
    ).bind(ulid(), songId, userId, reportNote, now, songId, userId, now, songId, userId),
    db.prepare(
      `UPDATE songs SET status = 'hidden', version = version + 1, updated_at = ?
       WHERE id = ? AND status = 'published' AND moderated_at IS NULL AND ${mine}
         AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = songs.created_by AND u.role IN ('owner', 'admin'))
         AND (SELECT COUNT(*) FROM song_flags f JOIN users u ON u.id = f.user_id
               WHERE f.song_id = ? AND u.google_sub IS NOT NULL) >= COALESCE(
           (SELECT CAST(value AS INTEGER) FROM settings WHERE key = 'flag_hide_threshold' AND CAST(value AS INTEGER) > 0),
           ${DEFAULT_FLAG_HIDE_THRESHOLD})`,
    ).bind(now, songId, songId, userId, now, songId),
  ]);
  if (ins.meta.changes > 0) return "added";
  const prior = await db
    .prepare("SELECT 1 AS x FROM song_flags WHERE song_id = ? AND user_id = ?")
    .bind(songId, userId)
    .first();
  return prior ? "repeat" : "rate_limited";
}
