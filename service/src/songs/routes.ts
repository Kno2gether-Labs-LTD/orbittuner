import { Hono } from "hono";
import type { AppEnv } from "../auth/devices";
import { ApiError } from "../lib/errors";
import { MAX_DOC_CHARS, validateSong } from "../chordpro/parse";
import { chordsOverLyricsToChordPro } from "../chordpro/plaintext";
import { readJsonBody as readJson } from "../lib/body";
import type { Env } from "../env";
import type { AuthUser } from "../auth/devices";
import { loadConvert, loadFetchPage } from "../ai/lazy";
import { aiConvertCharged, importStatus, MAX_URL_CHARS, withAiCharge } from "../ai/jobs";
import { checkAiRate, USER_AI_LIMIT_CODES } from "../auth/plans";
import { isAdmin } from "../auth/roles";
import { clientIpKey } from "../lib/ip";
import {
  FLAG_REASONS, MAX_CHANGE_IDS, blockedDedupeKeys, dedupeKey, browseSongs, findVisibleDuplicate, flagSong, getFacets, getSong, searchSongs,
  songChanges, upsertSong, visibleSongCreator, type FlagReason,
} from "./repo";

const MAX_NOTE_CHARS = 500;
const MAX_LANG_CHARS = 16;
const MAX_CATEGORIES = 50;
const MAX_CATEGORY_CHARS = 100;
const MAX_META_CHARS = 500;

export const songsApp = new Hono<AppEnv>();

function pageParam(v: string | undefined): number {
  const n = parseInt(v ?? "0", 10);
  return Number.isFinite(n) && n > 0 ? Math.min(n, 100_000) : 0;
}

songsApp.get("/v1/search", async (c) => {
  const q = c.req.query("q") ?? "";
  return c.json(
    await searchSongs(c.env.DB, c.var.user, {
      q: q.slice(0, 200),
      lang: c.req.query("lang") || undefined,
      cat: c.req.query("cat") || undefined,
      page: pageParam(c.req.query("page")),
    }),
  );
});

songsApp.get("/v1/browse", async (c) =>
  c.json(
    await browseSongs(c.env.DB, c.var.user, {
      lang: c.req.query("lang") || undefined,
      cat: c.req.query("cat") || undefined,
      page: pageParam(c.req.query("page")),
    }),
  ),
);

songsApp.get("/v1/facets", async (c) => c.json(await getFacets(c.env.DB, c.var.user)));

// Must be registered before /v1/songs/:id.
songsApp.get("/v1/songs/changes", async (c) => {
  const since = Number(c.req.query("since") ?? "0");
  if (!Number.isFinite(since) || since < 0) throw new ApiError(400, "invalid_since");
  const ids = [...new Set((c.req.query("ids") ?? "").split(",").map((s) => s.trim()).filter(Boolean))];
  if (ids.length > MAX_CHANGE_IDS) throw new ApiError(400, "too_many_ids");
  return c.json({ items: await songChanges(c.env.DB, c.var.user, since, ids) });
});

songsApp.get("/v1/songs/:id", async (c) => {
  const song = await getSong(c.env.DB, c.var.user, c.req.param("id"));
  if (!song) throw new ApiError(404, "not_found");
  return c.json(song);
});

/** Optional string field; wrong type -> 400. */
function optStr(b: Record<string, unknown>, k: string, max: number): string | undefined {
  const v = b[k];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "string") throw new ApiError(400, "invalid_input");
  if (v.length > max) throw new ApiError(k === "text" || k === "chordpro" ? 413 : 400, k === "text" || k === "chordpro" ? "too_large" : "invalid_input");
  return v;
}

const oneLine = (s: string | undefined) => s?.replace(/[\r\n{}]+/g, " ").trim() || undefined;

function toChordPro(text: string, title?: string, artist?: string): string {
  const normalised = text.replace(/\r\n|\r/g, "\n");
  const v = validateSong(normalised);
  if (v.ok) return normalised.trim() + "\n";
  return chordsOverLyricsToChordPro(normalised, { title: oneLine(title), artist: oneLine(artist) });
}

type AiMode = "auto" | "on" | "off";
const AUTO_PLAIN_FALLBACK = new Set(["no_chords", ...USER_AI_LIMIT_CODES]);

/** `ai` field: absent -> "off" (plain conversion only, as before AI existed). */
function aiMode(b: Record<string, unknown>): AiMode {
  const v = b.ai;
  if (v === undefined || v === null) return "off";
  if (v === "auto" || v === "on" || v === "off") return v;
  throw new ApiError(400, "invalid_input");
}

type Converted = {
  chordpro: string;
  language?: string;
  categories?: string[];
  via: "plain" | "ai";
  confidence?: number;
  sourceUrl?: string;
  /** Gateway id that produced an AI conversion. */
  provider?: string;
};

/**
 * Text: plain conversion, then AI when the mode asks for it. URL: SSRF check, then fetch + AI
 * (always). Every AI use is one request against the per-minute rate limit and one unit of the
 * user's plan allowance (refunded when no model call happened). AI errors (429 plan_limit_reached,
 * 429 ai_limit_reached, 422 ai_unparseable, ...) propagate.
 */
async function convertInput(
  env: Env, user: AuthUser,
  input: { text?: string; url?: string; mode: AiMode; title?: string; artist?: string },
): Promise<Converted> {
  const { shouldUseAi } = await loadConvert();
  if (input.text !== undefined) {
    const plain = toChordPro(input.text, input.title, input.artist);
    if (!shouldUseAi(plain, input.mode)) return { chordpro: plain, via: "plain" };
    let ai: Awaited<ReturnType<typeof aiConvertCharged>>;
    try {
      await checkAiRate(env, user);
      ai = await aiConvertCharged(env, user, { text: input.text, titleHint: input.title, artistHint: input.artist });
    } catch (e) {
      // auto: lyrics-only text, or a user over their plan or rate limit, keeps the plain conversion;
      // "on" reports the error.
      if (input.mode === "auto" && e instanceof ApiError && AUTO_PLAIN_FALLBACK.has(e.code)) {
        return { chordpro: plain, via: "plain" };
      }
      throw e;
    }
    return {
      chordpro: ai.chordpro, language: ai.language, categories: ai.categories, via: "ai",
      confidence: ai.confidence, provider: ai.providerId,
    };
  }
  const { assertPublicUrl, fetchPage } = await loadFetchPage();
  const url = assertPublicUrl(input.url!.trim()).href;
  await checkAiRate(env, user);
  const ai = await withAiCharge(env, user, async (convert) => {
    const page = await fetchPage(env, url);
    return convert(env, { text: page.text, sourceUrl: url, titleHint: input.title, artistHint: input.artist });
  });
  return {
    chordpro: ai.chordpro, language: ai.language, categories: ai.categories, via: "ai",
    confidence: ai.confidence, sourceUrl: url, provider: ai.providerId,
  };
}

songsApp.post("/v1/convert", async (c) => {
  const b = await readJson(c);
  const text = optStr(b, "text", MAX_DOC_CHARS);
  const url = optStr(b, "url", MAX_URL_CHARS);
  const title = optStr(b, "title", MAX_META_CHARS);
  const artist = optStr(b, "artist", MAX_META_CHARS);
  const mode = aiMode(b);
  if (text === undefined && url === undefined) throw new ApiError(400, "invalid_input");
  const r = await convertInput(c.env, c.var.user, { text, url, mode, title, artist });
  if (r.chordpro.length > MAX_DOC_CHARS) throw new ApiError(413, "too_large");
  return c.json({
    chordpro: r.chordpro,
    language: r.language ?? "en",
    categories: r.categories ?? [],
    via: r.via,
    ...(r.confidence !== undefined ? { confidence: r.confidence } : {}),
    ...(r.provider ? { provider: r.provider } : {}),
  });
});

songsApp.post("/v1/songs", async (c) => {
  const user = c.var.user;
  const b = await readJson(c);
  const chordproIn = optStr(b, "chordpro", MAX_DOC_CHARS);
  const text = optStr(b, "text", MAX_DOC_CHARS);
  const title = optStr(b, "title", MAX_META_CHARS);
  const artist = optStr(b, "artist", MAX_META_CHARS);
  let language = optStr(b, "language", MAX_LANG_CHARS)?.trim() || undefined;
  const url = optStr(b, "url", MAX_URL_CHARS);
  const mode = aiMode(b);
  let categories: string[] | undefined;
  if (b.categories !== undefined && b.categories !== null) {
    const cs = b.categories;
    if (
      !Array.isArray(cs) || cs.length > MAX_CATEGORIES ||
      !cs.every((x) => typeof x === "string" && x.length <= MAX_CATEGORY_CHARS)
    ) throw new ApiError(400, "invalid_input");
    categories = cs;
  }

  let chordpro: string;
  let source: "owner" | "web" | "import" = "owner";
  let confidence: number | undefined;
  let viaAi = false;
  let sourceUrl: string | undefined;
  if (chordproIn !== undefined) chordpro = chordproIn;
  else if (text !== undefined || url !== undefined) {
    const r = await convertInput(c.env, user, { text, url, mode, title, artist });
    chordpro = r.chordpro;
    if (r.via === "ai") {
      viaAi = true;
      source = url !== undefined && text === undefined ? "web" : "import";
      confidence = r.confidence;
      language ??= r.language;
      categories ??= r.categories;
      sourceUrl = r.sourceUrl;
    }
  } else throw new ApiError(400, "invalid_input");

  const v = validateSong(chordpro);
  if (!v.ok) throw new ApiError(v.error === "too_large" ? 413 : 422, v.error);
  const songArtist = v.doc.meta.artist?.trim() || null;
  const songTitle = v.doc.meta.title.trim();
  const dup = await findVisibleDuplicate(c.env.DB, user, songArtist, songTitle);
  if (dup) return c.json({ error: "duplicate", songId: dup }, 409);
  // A copy of a hidden or admin-moderated song (in any status, not visible to this user) never
  // auto-publishes, and is not refused either, so nothing about that song is revealed.
  const blocked = !isAdmin(user.role) && (await blockedDedupeKeys(c.env.DB, [dedupeKey(songArtist, songTitle)])).size > 0;

  const song = await upsertSong(c.env.DB, {
    chordpro, language, categories, source, sourceUrl,
    // AI results: confidence (missing -> 0) and the deterministic rule; hand-written or pasted
    // text: the deterministic rule. Anonymous accounts: always review (see importStatus).
    status: blocked ? "review" : importStatus(user, viaAi ? confidence ?? 0 : undefined, chordpro),
    createdBy: user.id,
  });
  return c.json(song, 201);
});

/**
 * Flag a song (`{reason?, note?}`; reason defaults to "other", note <= 500 chars). Idempotent per
 * user: 204 every time, one stored flag. 404 for a song the caller cannot see; 400 own_song for
 * the caller's own song (a creator cannot vote on their own contribution); 429 flag_rate_limited
 * past 20 new flags per user or 60 per IP (/64) in 24 h. Reaching the hide threshold hides the
 * song in the same transaction (see flagSong).
 */
songsApp.post("/v1/songs/:id/report", async (c) => {
  const user = c.var.user;
  const b = await readJson(c);
  const note = optStr(b, "note", MAX_NOTE_CHARS)?.trim() || null;
  const reason = b.reason ?? "other";
  if (typeof reason !== "string" || !(FLAG_REASONS as readonly string[]).includes(reason)) {
    throw new ApiError(400, "invalid_input");
  }
  const song = await visibleSongCreator(c.env.DB, user, c.req.param("id"));
  if (!song) throw new ApiError(404, "not_found");
  if (song.createdBy === user.id) throw new ApiError(400, "own_song");
  const ipKey = clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown");
  const r = await flagSong(c.env.DB, song.id, user.id, ipKey, reason as FlagReason, note);
  if (r === "rate_limited") throw new ApiError(429, "flag_rate_limited");
  return c.body(null, 204);
});
