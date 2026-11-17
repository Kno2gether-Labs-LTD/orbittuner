import { Unzip, UnzipInflate, UnzipPassThrough, zipSync, type UnzipFile } from "fflate";
import type { AuthUser } from "../auth/devices";
import { isAdmin } from "../auth/roles";
import { MAX_DOC_CHARS, validateSong } from "../chordpro/parse";
import { chordsOverLyricsToChordPro } from "../chordpro/plaintext";
import { ApiError } from "../lib/errors";
import type { Env } from "../env";
import { loadConvert } from "../ai/lazy";
import { aiConvertCharged, importStatus } from "../ai/jobs";
import { checkAiRate, USER_AI_LIMIT_CODES } from "../auth/plans";
import {
  blockedDedupeKeys, dedupeKey, listVisibleSongs, prepareInsert, songChanges, visibleDedupeKeys, type Song,
} from "../songs/repo";

export const MAX_ENTRY_BYTES = 1024 * 1024;
export const MAX_TOTAL_BYTES = 20 * 1024 * 1024;
export const MAX_ENTRIES = 2_000;
export const MAX_NEST_DEPTH = 1;
export const DEFAULT_IMPORT_MAX_SONGS = 200;
export const MAX_EXPORT_SONGS = 2_000;
const WRITE_CHUNK = 50;
// Compressed input is fed in small slices so one inflate step can only
// overshoot the caps by a bounded amount.
const FEED_SLICE = 4 * 1024;

export type ImportFile = { name: string; data: Uint8Array };
export type ImportResult = {
  added: number;
  duplicates: string[];
  failed: { name: string; error: string; message?: string }[];
};

type Budget = { bytes: number; entries: number };
type Meta = { language?: string; categories?: string[] };
type Entry = { name: string; data?: Uint8Array; error?: string };

class ArchiveLimit extends Error {}

/** Thrown (before anything is written) when an import would add more songs than allowed. */
export class TooManySongsError extends ApiError {
  constructor(public readonly max: number) {
    super(413, "too_many_songs");
  }
}

// ---------- helpers ----------

const baseName = (n: string) => n.split(/[\\/]/).pop() ?? n;

function decodeText(data: Uint8Array): string {
  // ignoreBOM:false makes the decoder consume a leading BOM.
  return new TextDecoder("utf-8", { fatal: false, ignoreBOM: false }).decode(data);
}

function badPath(name: string): boolean {
  return /^([\\/]|[A-Za-z]:)/.test(name) || name.split(/[\\/]/).includes("..") || name.includes("\0");
}

export function slugify(s: string): string {
  const slug = s
    .normalize("NFKD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60)
    .replace(/-+$/, "");
  return slug || "song";
}

const oneLine = (s: string) => s.replace(/[\r\n{}]+/g, " ").replace(/\s+/g, " ").trim();

/** "My Song - Artist.txt" -> title "My Song", artist "Artist". */
function titleFromFilename(name: string): { title: string; artist?: string } {
  const stem = baseName(name).replace(/\.[^.]*$/, "").replace(/_/g, " ");
  const i = stem.indexOf(" - ");
  if (i > 0) {
    const artist = oneLine(stem.slice(i + 3));
    return { title: oneLine(stem.slice(0, i)), artist: artist || undefined };
  }
  return { title: oneLine(stem) };
}

function cleanMeta(v: unknown): Meta {
  const out: Meta = {};
  if (typeof v !== "object" || v === null) return out;
  const o = v as Record<string, unknown>;
  if (typeof o.language === "string" && o.language.trim() && o.language.length <= 16) out.language = o.language.trim();
  if (
    Array.isArray(o.categories) && o.categories.length <= 50 &&
    o.categories.every((c) => typeof c === "string" && c.length <= 100)
  ) out.categories = o.categories as string[];
  return out;
}

// ---------- bounded unzip ----------

/** Streaming unzip that enforces per-entry, total-size and entry-count caps while inflating. */
function unzipBounded(data: Uint8Array, budget: Budget): Entry[] {
  const out: Entry[] = [];
  const uz = new Unzip();
  uz.register(UnzipInflate);
  uz.register(UnzipPassThrough);
  let limit = false;

  uz.onfile = (f: UnzipFile) => {
    if (limit || f.name.endsWith("/")) return;
    if (++budget.entries > MAX_ENTRIES) { limit = true; return; }
    if (badPath(f.name)) { out.push({ name: f.name, error: "invalid_name" }); return; }
    if (f.originalSize !== undefined && f.originalSize > MAX_ENTRY_BYTES) {
      out.push({ name: f.name, error: "too_large" });
      return;
    }
    const chunks: Uint8Array[] = [];
    let size = 0;
    let dead = false;
    f.ondata = (err, chunk, final) => {
      if (dead || limit) return;
      if (err) { dead = true; out.push({ name: f.name, error: "invalid_zip" }); return; }
      size += chunk.length;
      budget.bytes += chunk.length;
      if (budget.bytes > MAX_TOTAL_BYTES) { limit = true; dead = true; f.terminate(); return; }
      if (size > MAX_ENTRY_BYTES) { dead = true; f.terminate(); out.push({ name: f.name, error: "too_large" }); return; }
      chunks.push(chunk);
      if (final) {
        const buf = new Uint8Array(size);
        let off = 0;
        for (const c of chunks) { buf.set(c, off); off += c.length; }
        out.push({ name: f.name, data: buf });
      }
    };
    try {
      f.start();
    } catch {
      dead = true;
      out.push({ name: f.name, error: "unsupported_compression" });
    }
  };

  try {
    if (data.length === 0) throw new Error("empty");
    for (let i = 0; i < data.length && !limit; i += FEED_SLICE) {
      uz.push(data.subarray(i, i + FEED_SLICE), i + FEED_SLICE >= data.length);
    }
  } catch {
    if (!limit) throw new Error("invalid_zip");
  }
  if (limit) throw new ArchiveLimit();
  if (out.length === 0 && !looksLikeZip(data)) throw new Error("invalid_zip");
  return out;
}

const looksLikeZip = (d: Uint8Array) => d.length >= 4 && d[0] === 0x50 && d[1] === 0x4b;

// ---------- import ----------

export type AiMode = "auto" | "on" | "off";
type Candidate = { name: string; chordpro: string; meta: Meta; key: string; confidence?: number };
/** A .txt file waiting for AI conversion (after the in-memory pass, before any write). */
type AiPending = { name: string; text: string; plain: string; meta: Meta; titleHint?: string; artistHint?: string };
type Ctx = {
  /** Set only when ai !== "off" (loaded lazily, see ai/lazy.ts). */
  shouldUseAi?: (plain: string | null, mode: AiMode) => boolean;
  budget: Budget;
  maxSongs: number;
  ai: AiMode;
  candidates: Candidate[];
  aiPending: AiPending[];
  failed: ImportResult["failed"];
  /** An archive-level cap was hit: nothing is written. */
  aborted: boolean;
};

/** Phase (a): validate in memory and queue the song for phase (b). No DB access. */
function collect(ctx: Ctx, name: string, chordpro: string, meta: Meta, confidence?: number) {
  const v = validateSong(chordpro);
  if (!v.ok) {
    ctx.failed.push({ name, error: v.error });
    return;
  }
  reserveSong(ctx);
  ctx.candidates.push({
    name, chordpro, meta, confidence,
    key: dedupeKey(v.doc.meta.artist?.trim() || null, v.doc.meta.title.trim()),
  });
}

/** Files awaiting AI count toward the song cap, so the cap is enforced before any AI call. */
function reserveSong(ctx: Ctx) {
  if (ctx.candidates.length + ctx.aiPending.length >= ctx.maxSongs) throw new TooManySongsError(ctx.maxSongs);
}

function archiveTooLarge(ctx: Ctx, name: string) {
  ctx.aborted = true;
  ctx.failed.push({ name, error: "archive_too_large" });
}

function plainToChordPro(text: string, filename: string): string {
  const normalised = text.replace(/\r\n|\r/g, "\n");
  if (validateSong(normalised).ok) return normalised.trim() + "\n";
  return chordsOverLyricsToChordPro(normalised, titleFromFilename(filename));
}

function importJsonBackup(ctx: Ctx, name: string, data: Uint8Array) {
  let parsed: unknown;
  try {
    parsed = JSON.parse(decodeText(data));
  } catch {
    ctx.failed.push({ name, error: "invalid_json" });
    return;
  }
  const songs = (parsed as { songs?: unknown })?.songs;
  if (!Array.isArray(songs)) {
    ctx.failed.push({ name, error: "invalid_json" });
    return;
  }
  let i = 0;
  for (const s of songs) {
    i++;
    // Backup songs are entries too: they share the per-request entry budget.
    if (++ctx.budget.entries > MAX_ENTRIES) {
      archiveTooLarge(ctx, name);
      return;
    }
    const label = `${name}#${i}`;
    const cp = (s as { chordpro?: unknown } | null)?.chordpro;
    if (typeof cp !== "string") {
      ctx.failed.push({ name: label, error: "invalid_json" });
      continue;
    }
    guarded(ctx, label, () => collect(ctx, label, cp, cleanMeta(s)));
  }
}

function guarded(ctx: Ctx, name: string, fn: () => void) {
  try {
    fn();
  } catch (e) {
    if (e instanceof TooManySongsError) throw e;
    ctx.failed.push({ name, error: e instanceof ApiError ? e.code : "import_failed" });
  }
}

function importOne(ctx: Ctx, name: string, data: Uint8Array, depth: number, meta: Meta) {
  const base = baseName(name);
  if (base === "" || base.startsWith("._") || base === ".DS_Store" || /(^|[\\/])__MACOSX[\\/]/.test(name)) return;
  const ext = (/\.([^.]+)$/.exec(base)?.[1] ?? "").toLowerCase();

  if (ext === "zip") {
    if (depth > MAX_NEST_DEPTH) {
      ctx.failed.push({ name, error: "nested_too_deep" });
      return;
    }
    importZip(ctx, name, data, depth + 1);
    return;
  }
  if (data.length > MAX_ENTRY_BYTES) {
    ctx.failed.push({ name, error: "too_large" });
    return;
  }
  if (ext === "json") return importJsonBackup(ctx, name, data);
  if (ext === "cho" || ext === "chopro" || ext === "chordpro" || ext === "crd") {
    return collect(ctx, name, decodeText(data), meta);
  }
  if (ext === "txt") {
    const text = decodeText(data);
    if (text.length > MAX_DOC_CHARS) {
      ctx.failed.push({ name, error: "too_large" });
      return;
    }
    const plain = plainToChordPro(text, base);
    if (ctx.shouldUseAi?.(plain, ctx.ai)) {
      reserveSong(ctx);
      ctx.aiPending.push({ name, text, plain, meta, ...hints(base) });
      return;
    }
    return collect(ctx, name, plain, meta);
  }
  ctx.failed.push({ name, error: "unsupported_type" });
}

function importZip(ctx: Ctx, name: string, data: Uint8Array, depth: number) {
  let entries: Entry[];
  try {
    entries = unzipBounded(data, ctx.budget);
  } catch (e) {
    if (e instanceof ArchiveLimit) archiveTooLarge(ctx, name);
    else ctx.failed.push({ name, error: "invalid_zip" });
    return;
  }
  // manifest.json (as written by exportZip) supplies language/categories per file.
  const metaByFile = new Map<string, Meta>();
  for (const e of entries) {
    if (!e.data || baseName(e.name).toLowerCase() !== "manifest.json") continue;
    try {
      const m = JSON.parse(decodeText(e.data));
      if (Array.isArray(m)) {
        for (const it of m) if (typeof it?.file === "string") metaByFile.set(it.file, cleanMeta(it));
      }
    } catch { /* a bad manifest only loses metadata */ }
  }
  for (const e of entries) {
    if (baseName(e.name).toLowerCase() === "manifest.json" && e.data) continue;
    if (e.error) {
      ctx.failed.push({ name: e.name, error: e.error });
      continue;
    }
    guarded(ctx, e.name, () => importOne(ctx, e.name, e.data!, depth, metaByFile.get(e.name) ?? {}));
  }
}

function hints(base: string): { titleHint?: string; artistHint?: string } {
  const t = titleFromFilename(base);
  return { titleHint: t.title || undefined, artistHint: t.artist };
}

/**
 * Still before any write: AI-convert the pending .txt files one at a time. Once the daily AI
 * budget runs out, the remaining pending files fail with ai_limit_reached; others still import.
 */
export const DEFAULT_AI_IMPORT_SYNC_MAX = 10;
const AI_BATCH_LIMIT_MESSAGE =
  "Too many files need AI conversion in one upload. Upload the rest in smaller batches, or add songs by link with the Links import.";

async function aiSyncMax(db: D1Database): Promise<number> {
  const row = await db.prepare("SELECT value FROM settings WHERE key = 'ai_import_sync_max'").first<{ value: string }>();
  const n = parseInt(row?.value ?? "", 10);
  return Number.isFinite(n) && n >= 0 ? n : DEFAULT_AI_IMPORT_SYNC_MAX;
}

/**
 * Still before any write: AI-convert the pending .txt files one at a time, at most
 * `ai_import_sync_max` per upload (the rest fail ai_batch_limit). The upload is one request
 * against the per-minute AI rate limit; each file costs one unit of the user's plan allowance.
 * Once the service AI budget or the user's plan/rate limit runs out, the remaining pending files
 * fail with that code (auto mode keeps their plain conversion instead); other files still import.
 */
async function convertPending(ctx: Ctx, env: Env, user: AuthUser) {
  // Their cap slots were reserved in phase (a); release them as each one resolves.
  const pending = ctx.aiPending;
  ctx.aiPending = [];
  const max = await aiSyncMax(env.DB);
  let stopCode: string | null = null;
  let plainOnly = false; // auto mode past the user's plan/rate limit: no more AI attempts
  try {
    if (pending.length) await checkAiRate(env, user);
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    if (ctx.ai === "auto") plainOnly = true;
    else stopCode = e.code;
  }
  for (const [i, p] of pending.entries()) {
    if (i >= max) {
      ctx.failed.push({ name: p.name, error: "ai_batch_limit", message: AI_BATCH_LIMIT_MESSAGE });
      continue;
    }
    if (stopCode) {
      ctx.failed.push({ name: p.name, error: stopCode });
      continue;
    }
    if (plainOnly) {
      guarded(ctx, p.name, () => collect(ctx, p.name, p.plain, p.meta));
      continue;
    }
    try {
      const r = await aiConvertCharged(env, user, { text: p.text, titleHint: p.titleHint, artistHint: p.artistHint });
      collect(ctx, p.name, r.chordpro, {
        language: p.meta.language ?? r.language, categories: p.meta.categories ?? r.categories,
      }, r.confidence);
    } catch (e) {
      // auto: a lyrics-only file, or one past the user's plan limit, keeps its plain conversion.
      const userLimit = e instanceof ApiError && USER_AI_LIMIT_CODES.has(e.code);
      if (ctx.ai === "auto" && e instanceof ApiError && (e.code === "no_chords" || userLimit)) {
        if (userLimit) plainOnly = true;
        guarded(ctx, p.name, () => collect(ctx, p.name, p.plain, p.meta));
        continue;
      }
      if (e instanceof ApiError && (e.code === "ai_limit_reached" || userLimit)) stopCode = e.code;
      ctx.failed.push({ name: p.name, error: e instanceof ApiError ? e.code : "import_failed" });
    }
  }
}

export async function readMaxSongs(db: D1Database): Promise<number> {
  const row = await db
    .prepare("SELECT value FROM settings WHERE key = 'import_max_songs'")
    .first<{ value: string }>();
  const n = parseInt(row?.value ?? "", 10);
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_IMPORT_MAX_SONGS;
}

/**
 * Phase (a) parses, inflates and converts everything in memory (no DB); phase (b) writes with one
 * duplicate query and chunked batches. Throws TooManySongsError before any write when the upload
 * holds more candidate songs than `import_max_songs`; an archive-level cap aborts with no writes.
 */
export async function importFiles(
  db: D1Database,
  user: AuthUser,
  files: ImportFile[],
  opts: { ai?: AiMode; env?: Env } = {},
): Promise<ImportResult> {
  const ai = opts.env ? opts.ai ?? "off" : "off";
  const ctx: Ctx = {
    budget: { bytes: 0, entries: 0 }, maxSongs: await readMaxSongs(db), ai,
    candidates: [], aiPending: [], failed: [], aborted: false,
    shouldUseAi: ai === "off" ? undefined : (await loadConvert()).shouldUseAi,
  };
  for (const f of files) {
    ctx.budget.entries++;
    ctx.budget.bytes += f.data.length;
    if (ctx.budget.entries > MAX_ENTRIES || ctx.budget.bytes > MAX_TOTAL_BYTES) {
      archiveTooLarge(ctx, f.name);
      continue;
    }
    guarded(ctx, f.name, () => importOne(ctx, f.name, f.data, 0, {}));
  }

  const result: ImportResult = { added: 0, duplicates: [], failed: ctx.failed };
  if (ctx.aborted) return result;
  if (ctx.aiPending.length) await convertPending(ctx, opts.env!, user);
  if (ctx.candidates.length === 0) return result;

  const keys = [...new Set(ctx.candidates.map((c) => c.key))];
  const existing = await visibleDedupeKeys(db, user, keys);
  const blocked = isAdmin(user.role) ? new Set<string>() : await blockedDedupeKeys(db, keys);
  const seen = new Set<string>();
  const fresh: Candidate[] = [];
  for (const c of ctx.candidates) {
    if (existing.has(c.key) || seen.has(c.key)) result.duplicates.push(c.name);
    else { seen.add(c.key); fresh.push(c); }
  }
  for (let i = 0; i < fresh.length; i += WRITE_CHUNK) {
    const chunk = fresh.slice(i, i + WRITE_CHUNK);
    try {
      const stmts = chunk.flatMap((c) =>
        prepareInsert(db, {
          chordpro: c.chordpro, language: c.meta.language, categories: c.meta.categories,
          source: "import", createdBy: user.id,
          // Non-AI file imports by users always go to review (no chordpro passed: a bulk upload is
          // not "hand-written"); AI results follow importStatus. Copies of hidden/moderated songs: review.
          status: blocked.has(c.key) && !isAdmin(user.role)
            ? "review"
            : importStatus(user, c.confidence, c.confidence !== undefined ? c.chordpro : undefined),
        }).stmts,
      );
      await db.batch(stmts);
      result.added += chunk.length;
    } catch (e) {
      const error = e instanceof ApiError ? e.code : "import_failed";
      for (const c of chunk) result.failed.push({ name: c.name, error });
    }
  }
  return result;
}

// ---------- export ----------

export async function exportZip(db: D1Database, user: AuthUser, songIds?: string[]): Promise<Uint8Array> {
  let songs: Song[];
  if (songIds) {
    const ids = [...new Set(songIds)];
    const found = new Map((await songChanges(db, user, 0, ids)).map((s) => [s.id, s]));
    songs = ids.flatMap((id) => (found.has(id) ? [found.get(id)!] : []));
  } else {
    songs = await listVisibleSongs(db, user, MAX_EXPORT_SONGS + 1);
    if (songs.length > MAX_EXPORT_SONGS) throw new ApiError(413, "too_many_songs");
  }
  const enc = new TextEncoder();
  const files: Record<string, Uint8Array> = {};
  const manifest = songs.map((s) => {
    const file = `songs/${slugify(s.title)}-${s.id}.cho`;
    files[file] = enc.encode(s.chordpro);
    return { id: s.id, file, language: s.language, categories: s.categories, status: s.status };
  });
  files["manifest.json"] = enc.encode(JSON.stringify(manifest));
  return zipSync(files);
}

/** Owner backup: every song plus the owner accounts' playlists and favourites. */
export async function exportJson(db: D1Database): Promise<string> {
  const owners = (await db.prepare("SELECT id FROM users WHERE role = 'owner'").all<{ id: string }>()).results;
  const ownerIds = JSON.stringify(owners.map((o) => o.id));
  const [songs, pls, favs] = await Promise.all([
    listVisibleSongs(db, { id: "", name: "", role: "owner", linked: true }),
    db.prepare(
      `SELECT p.id, p.name, p.deleted, p.updated_at, i.song_id, i.position, i.settings
         FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
        WHERE p.user_id IN (SELECT value FROM json_each(?))
        ORDER BY p.updated_at, p.id, i.position, i.song_id`,
    ).bind(ownerIds).all<{
      id: string; name: string; deleted: number; updated_at: number;
      song_id: string | null; position: string | null; settings: string | null;
    }>(),
    db.prepare(
      "SELECT song_id, favourite, updated_at FROM favourites WHERE user_id IN (SELECT value FROM json_each(?)) ORDER BY song_id",
    ).bind(ownerIds).all<{ song_id: string; favourite: number; updated_at: number }>(),
  ]);
  const playlists: { id: string; name: string; deleted: boolean; updatedAt: number; items: unknown[] }[] = [];
  for (const r of pls.results) {
    let p = playlists[playlists.length - 1];
    if (!p || p.id !== r.id) {
      p = { id: r.id, name: r.name, deleted: r.deleted === 1, updatedAt: r.updated_at, items: [] };
      playlists.push(p);
    }
    if (r.song_id != null) {
      let settings: unknown = {};
      try { settings = JSON.parse(r.settings ?? "{}"); } catch { /* keep {} */ }
      p.items.push({ songId: r.song_id, position: r.position, settings });
    }
  }
  return JSON.stringify({
    version: 1,
    songs,
    playlists,
    favourites: favs.results.map((f) => ({ songId: f.song_id, favourite: f.favourite === 1, updatedAt: f.updated_at })),
  });
}
