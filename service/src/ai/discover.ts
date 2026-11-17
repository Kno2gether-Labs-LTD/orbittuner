import type { AuthUser } from "../auth/devices";
import type { Env } from "../env";
import { aiRemaining, checkAiRate, reserveAiUpTo, type AiReservation } from "../auth/plans";
import { ApiError } from "../lib/errors";
import { getSecret } from "../lib/secrets";
import { visibleSongsBySourceUrl } from "../songs/repo";
import { insertJob, type NewJobItem } from "./jobs";
import { loadFetchPage } from "./lazy";
import { refundSearch, reserveSearch } from "./searchQuota";

/**
 * Find on the web: POST /v1/discover {q, lang?}.
 *
 * 1. One AI-capable request against the per-minute rate limit; refused up front (429
 *    plan_limit_reached) when the user has no AI unit left today, so an exhausted user never
 *    costs an Apify run.
 * 2. Candidates: the global cache for the normalised query (+ lang), else one Apify Google
 *    Search run for "<q> chords". An uncached search first takes one unit of the daily search
 *    quotas (per plan, per client IP, global breaker; see searchQuota.ts), refunded when Apify
 *    fails; cache hits are free. Organic results are filtered (video, social, streaming,
 *    shopping, encyclopedia hosts dropped), known chord sites ranked first, and the top 3 that
 *    pass the SSRF guard kept. The cache holds only that URL list: 24 h when something was
 *    found, 1 h when every result was filtered out, never when Google returned no organic
 *    results. Jobs stay private to their owner, so a cache hit creates a NEW job for the caller
 *    from the cached URLs without another search. The search and cache write run under
 *    waitUntil, so a client that disconnects mid-search still leaves the result cached.
 * 3. A candidate already imported as a song the caller can see is recorded as a free `duplicate`
 *    item. The rest reserve min(count, units left) plan units (partial reservation); the excess is
 *    recorded `limit_reached` (plan_limit_reached). Queued items run through the link-import
 *    consumer (fetch -> AI -> dedupe -> publish/review per importStatus) with its refund rules.
 *
 * Returns 202 {jobId, candidates} or 200 {jobId: null, results: 0} when nothing usable was found.
 */

export const MAX_CANDIDATES = 3;
export const CACHE_TTL_MS = 24 * 3600_000;
/** TTL when Google returned results but every one was filtered out. */
export const EMPTY_CACHE_TTL_MS = 3600_000;
/** Languages accepted for `lang` (passed to the actor as the Google interface language). */
export const LANGS = new Set(["en", "hi", "bn", "ta", "te", "ml", "kn", "mr", "gu", "pa", "ur"]);
export const MIN_Q = 2;
export const MAX_Q = 120;
export const DEFAULT_SEARCH_ACTOR = "apify~google-search-scraper";
const ACTOR_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}~[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/;
const APIFY_RUN_TIMEOUT_S = 45;
const APIFY_TIMEOUT_MS = 55_000;
const APIFY_MAX_BYTES = 2 * 1024 * 1024;
/** Organic results considered at most (one SERP page is ~10). */
const MAX_RESULTS_SCANNED = 50;

/** Hosts (and their subdomains) never worth importing: video, social, streaming, shopping, wiki. */
const BLOCKED_DOMAINS = [
  "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.com", "fb.watch",
  "twitter.com", "x.com", "spotify.com", "music.apple.com", "itunes.apple.com", "podcasts.apple.com",
  "jiosaavn.com", "saavn.com", "gaana.com", "wynk.in", "music.amazon.com", "soundcloud.com",
  "wikipedia.org", "flipkart.com", "vimeo.com", "dailymotion.com", "reddit.com", "redd.it",
  "threads.net", "threads.com", "linkedin.com", "shazam.com", "hungama.com", "googleusercontent.com",
];
/** A host with one of these labels anywhere is blocked too (pinterest.co.uk, amazon.in, google.co.in, ...). */
const BLOCKED_LABELS = new Set(["pinterest", "amazon", "google"]);
/** Known chord-sheet sites, ranked first in this order. */
const PREFERRED_DOMAINS = [
  "ultimate-guitar.com", "e-chords.com", "chordu.com", "gaanachords.com", "chordsworld.com",
  "chordsbase.com", "guitartabsexplorer.com", "chordie.com", "worshiptogether.com",
];

const invalid = () => new ApiError(400, "invalid_input");
const searchFailed = () => new ApiError(502, "search_failed");

/** Cache/job key form: NFKC, lower case, single spaces, no trailing "chords". */
export function normaliseQuery(q: string): string {
  return q
    .normalize("NFKC")
    .replace(/\p{Cf}+/gu, "") // zero-width and other invisible format characters
    .toLowerCase()
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/(\s+chords?)+$/, "");
}

const hostIs = (host: string, domain: string) => host === domain || host.endsWith("." + domain);

function blocked(host: string): boolean {
  return BLOCKED_DOMAINS.some((d) => hostIs(host, d)) || host.split(".").some((l) => BLOCKED_LABELS.has(l));
}

function rank(host: string): number {
  const i = PREFERRED_DOMAINS.findIndex((d) => hostIs(host, d));
  if (i >= 0) return i;
  return host.includes("chord") ? PREFERRED_DOMAINS.length : PREFERRED_DOMAINS.length + 1;
}

/** http(s) URLs with no blocked host, first occurrence kept, known chord sites first (stable). */
export function rankCandidates(urls: string[]): string[] {
  const seen = new Set<string>();
  const out: { url: string; r: number; i: number }[] = [];
  for (const raw of urls) {
    let u: URL;
    try {
      u = new URL(raw);
    } catch {
      continue;
    }
    if (u.protocol !== "http:" && u.protocol !== "https:") continue;
    const host = u.hostname.toLowerCase().replace(/\.+$/, "");
    if (blocked(host)) continue;
    if (seen.has(u.href)) continue;
    seen.add(u.href);
    out.push({ url: u.href, r: rank(host), i: out.length });
  }
  return out.sort((a, b) => a.r - b.r || a.i - b.i).map((c) => c.url);
}

export function parseDiscoverBody(b: Record<string, unknown>): { q: string; lang: string | null } {
  if (typeof b.q !== "string") throw invalid();
  const raw = b.q.trim();
  if (raw.length < MIN_Q || raw.length > MAX_Q) throw invalid();
  // A song name, not a link: links go to POST /v1/import/links.
  if (/:\/\/|^www\./i.test(raw)) throw invalid();
  const q = normaliseQuery(raw);
  // Re-checked after normalisation: NFKC can expand a short input, \p{Cf} stripping can empty it.
  if (q.length < MIN_Q || q.length > MAX_Q) throw invalid();
  let lang: string | null = null;
  if (b.lang !== undefined && b.lang !== null) {
    if (typeof b.lang !== "string" || !LANGS.has(b.lang)) throw invalid();
    lang = b.lang;
  }
  return { q, lang };
}

/** The Apify Google Search actor (settings apify_search_actor, editable on /portal/ai). */
async function searchActor(env: Env): Promise<string> {
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_search_actor'")
      .first<{ value: string | null }>();
    const v = row?.value?.trim();
    if (v && ACTOR_RE.test(v)) return v;
  } catch {
    /* settings unavailable: use the default */
  }
  return DEFAULT_SEARCH_ACTOR;
}

/** Organic result URLs for "<q> chords", in Google's order. The token never leaves this function. */
async function searchWeb(env: Env, q: string, lang: string | null): Promise<string[]> {
  let token: string | undefined;
  try {
    token = await getSecret(env, "APIFY_TOKEN");
  } catch {
    token = undefined;
  }
  if (!token) throw new ApiError(503, "search_unavailable");
  const actor = await searchActor(env);
  const { readCapped } = await loadFetchPage();
  // Input per the actor's schema: `queries` is a newline-separated string; one SERP page (~10 results).
  const input: Record<string, unknown> = { queries: `${q} chords`, maxPagesPerQuery: 1 };
  if (lang) input.languageCode = lang;
  try {
    const res = await fetch(
      `https://api.apify.com/v2/acts/${actor}/run-sync-get-dataset-items?timeout=${APIFY_RUN_TIMEOUT_S}`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify(input),
        signal: AbortSignal.timeout(APIFY_TIMEOUT_MS),
      },
    );
    if (!res.ok) {
      await res.body?.cancel().catch(() => {});
      throw searchFailed();
    }
    const items: unknown = JSON.parse(new TextDecoder().decode(await readCapped(res, APIFY_MAX_BYTES)));
    if (!Array.isArray(items)) throw searchFailed();
    const urls: string[] = [];
    for (const item of items) {
      const organic = (item as { organicResults?: unknown } | null)?.organicResults;
      if (!Array.isArray(organic)) continue;
      for (const r of organic) {
        const url = (r as { url?: unknown } | null)?.url;
        if (typeof url === "string" && url.length <= 2048) urls.push(url);
        if (urls.length >= MAX_RESULTS_SCANNED) return urls;
      }
    }
    return urls;
  } catch {
    // Swallow the original error: it may echo request headers (the token).
    throw searchFailed();
  }
}

export type DiscoverCtx = { ipKey: string; waitUntil?: (p: Promise<unknown>) => void };

async function cachedCandidates(env: Env, key: string, now: number): Promise<string[] | null> {
  const hit = await env.DB.prepare(
    "SELECT urls FROM discover_cache WHERE query_key = ? AND COALESCE(expires_at, created_at + ?) > ?",
  ).bind(key, CACHE_TTL_MS, now).first<{ urls: string }>();
  if (!hit) return null;
  try {
    const v: unknown = JSON.parse(hit.urls);
    if (Array.isArray(v) && v.every((u) => typeof u === "string")) return v as string[];
  } catch {
    /* corrupt row: search again */
  }
  return null;
}

/** One paid search (quota taken first, refunded when Apify fails), then the cache write. */
async function searchAndCache(
  env: Env, user: AuthUser, key: string, q: string, lang: string | null, ipKey: string,
): Promise<string[]> {
  const reservation = await reserveSearch(env, user, ipKey);
  let raw: string[];
  try {
    raw = await searchWeb(env, q, lang);
  } catch (e) {
    await refundSearch(env, reservation).catch(() => console.error("search refund failed"));
    throw e;
  }
  const { assertPublicUrl } = await loadFetchPage();
  const urls = rankCandidates(raw).filter((u) => {
    try {
      assertPublicUrl(u);
      return true;
    } catch {
      return false;
    }
  }).slice(0, MAX_CANDIDATES);
  // No organic results at all may be a blocked or broken SERP: never cached.
  if (raw.length > 0) {
    const now = Date.now();
    try {
      await env.DB.batch([
        env.DB.prepare("DELETE FROM discover_cache WHERE COALESCE(expires_at, created_at + ?) <= ?").bind(CACHE_TTL_MS, now),
        env.DB.prepare(
          `INSERT INTO discover_cache (query_key, urls, created_at, expires_at) VALUES (?, ?, ?, ?)
           ON CONFLICT(query_key) DO UPDATE SET urls = excluded.urls, created_at = excluded.created_at,
             expires_at = excluded.expires_at`,
        ).bind(key, JSON.stringify(urls), now, now + (urls.length > 0 ? CACHE_TTL_MS : EMPTY_CACHE_TTL_MS)),
      ]);
    } catch {
      console.error("discover cache write failed"); // the search result is still usable
    }
  }
  return urls;
}

async function candidatesFor(env: Env, user: AuthUser, q: string, lang: string | null, ctx: DiscoverCtx): Promise<string[]> {
  const key = `${lang ?? ""}|${q}`;
  const cached = await cachedCandidates(env, key, Date.now());
  if (cached) return cached;
  const work = searchAndCache(env, user, key, q, lang, ctx.ipKey);
  // Keep the search and cache write alive if the client disconnects; still await the result.
  ctx.waitUntil?.(work.catch(() => {}));
  return work;
}

export type DiscoverResult = { jobId: string; candidates: number } | { jobId: null; results: 0 };

export async function discover(
  env: Env, user: AuthUser, q: string, lang: string | null, ctx: DiscoverCtx,
): Promise<DiscoverResult> {
  await checkAiRate(env, user);
  const quota = await aiRemaining(env, user);
  if (quota.remaining === 0) throw new ApiError(429, "plan_limit_reached", { limit: quota.limit, plan: quota.plan });

  const urls = await candidatesFor(env, user, q, lang, ctx);
  if (urls.length === 0) return { jobId: null, results: 0 };

  const existing = await visibleSongsBySourceUrl(env.DB, user, urls);
  const fresh = urls.filter((u) => !existing.has(u)).length;
  let reservation: AiReservation = null;
  let granted = 0;
  if (fresh > 0) {
    try {
      ({ reservation, granted } = await reserveAiUpTo(env, user, fresh));
    } catch (e) {
      // Spent by a concurrent request since the pre-check: still record the free duplicates.
      if (!(e instanceof ApiError && e.code === "plan_limit_reached") || existing.size === 0) throw e;
    }
  }
  let toQueue = granted;
  const items = urls.map((u): NewJobItem => {
    const songId = existing.get(u);
    if (songId) return { u, s: "duplicate", e: null, id: songId };
    if (toQueue > 0) {
      toQueue--;
      return { u, s: "queued", e: null };
    }
    return { u, s: "limit_reached", e: "plan_limit_reached" };
  });
  const jobId = await insertJob(env, user, items, reservation, { kind: "discover", query: q });
  return { jobId, candidates: urls.length };
}
