import type { AuthUser } from "../auth/devices";
import { ftsSummaries, type SongSummary } from "../songs/repo";
import type { Guess, Understanding } from "./understand";

export type MatchKind = "lyrics" | "title";
export type Result = SongSummary & { match: MatchKind };

export const MAX_RESULTS = 10;
/** Words of one text considered at most (a 20 s line is well under this). */
const MAX_TOKENS = 24;
/** Words in the token-AND fallback query. */
const MAX_AND_TOKENS = 12;
const LYRIC_RUN = { max: 6, min: 3 };
const TITLE_RUN = { max: 6, min: 2 };

/**
 * Sanitised search words: letters, combining marks and digits only (marks keep Indic words such
 * as "तुम" whole, matching the unicode61 tokenizer), lower case. Safe to wrap in FTS quotes.
 */
export function voiceTokens(s: string): string[] {
  return (s.normalize("NFKC").toLowerCase().match(/[\p{L}\p{N}][\p{L}\p{M}\p{N}]*/gu) ?? []).slice(0, MAX_TOKENS);
}

const quote = (words: string[]) => `"${words.join(" ")}"`;

/** Distinct runs of `len` consecutive words, as FTS phrases. */
function runs(words: string[], len: number): string[] {
  const out = new Set<string>();
  for (let i = 0; i + len <= words.length; i++) out.add(quote(words.slice(i, i + len)));
  return [...out];
}

/**
 * The longest run length (max..min words) whose phrases hit `column`, as one OR query per length;
 * the first length with any hit wins. A text shorter than `min` words is tried whole.
 */
async function longestRuns(
  db: D1Database, user: AuthUser, column: "lyrics" | "title", words: string[], span: { max: number; min: number },
): Promise<SongSummary[]> {
  const top = Math.min(span.max, words.length);
  const bottom = Math.min(span.min, words.length);
  for (let len = top; len >= bottom && len > 0; len--) {
    const hits = await ftsSummaries(db, user, `${column} : (${runs(words, len).join(" OR ")})`, MAX_RESULTS);
    if (hits.length > 0) return hits;
  }
  return [];
}

/** Lyrics: longest 3-6 word phrase runs, then every word (AND). */
async function lyricMatches(db: D1Database, user: AuthUser, text: string): Promise<SongSummary[]> {
  const words = voiceTokens(text);
  if (words.length === 0) return [];
  const out = words.length >= LYRIC_RUN.min ? await longestRuns(db, user, "lyrics", words, LYRIC_RUN) : [];
  if (out.length < MAX_RESULTS) {
    const all = [...new Set(words)].slice(0, MAX_AND_TOKENS).map((w) => `"${w}"`).join(" AND ");
    out.push(...(await ftsSummaries(db, user, `lyrics : (${all})`, MAX_RESULTS)));
  }
  return out;
}

/** Titles containing a 2-6 word run of the spoken text (so "play amazing grace" finds "Amazing Grace"). */
async function titleTextMatches(db: D1Database, user: AuthUser, text: string): Promise<SongSummary[]> {
  const words = voiceTokens(text);
  return words.length === 0 ? [] : longestRuns(db, user, "title", words, TITLE_RUN);
}

/** Songs whose title has every word of the guess; songs by the guessed artist first (tie-break). */
async function guessMatches(db: D1Database, user: AuthUser, g: Guess): Promise<SongSummary[]> {
  const words = [...new Set(voiceTokens(g.title))].slice(0, MAX_AND_TOKENS);
  if (words.length === 0) return [];
  const hits = await ftsSummaries(db, user, `title : (${words.map((w) => `"${w}"`).join(" AND ")})`, MAX_RESULTS);
  const want = new Set(voiceTokens(g.artist ?? ""));
  if (want.size === 0) return hits;
  const score = (s: SongSummary) => {
    const have = new Set(voiceTokens(s.artist ?? ""));
    let n = 0;
    for (const w of want) if (have.has(w)) n++;
    return n / want.size;
  };
  return hits.map((s, i) => ({ s, i, k: score(s) })).sort((a, b) => b.k - a.k || a.i - b.i).map((x) => x.s);
}

/**
 * Catalogue matches for one understood clip, ≤ 10, deduped (first match kind wins). Always
 * searches titles AND lyrics with both the romanized text and the raw transcript:
 * - intent "request": guess titles (artist breaks ties), then titles containing the spoken
 *   phrase, then lyric lines;
 * - intent "lyrics" (also the LLM-down fallback): lyric lines first, then the title searches.
 * Visibility is the same as /v1/search (published, or the caller's own; staff see all).
 */
export async function matchSongs(db: D1Database, user: AuthUser, u: Understanding, transcript: string): Promise<Result[]> {
  const texts: string[] = [];
  const seenText = new Set<string>();
  for (const t of [u.romanized, transcript]) {
    const k = voiceTokens(t).join(" ");
    if (k && !seenText.has(k)) { seenText.add(k); texts.push(t); }
  }
  type Source = { kind: MatchKind; find: () => Promise<SongSummary[]> };
  const lyrics: Source[] = texts.map((t) => ({ kind: "lyrics", find: () => lyricMatches(db, user, t) }));
  const titles: Source[] = [
    ...u.guesses.map((g): Source => ({ kind: "title", find: () => guessMatches(db, user, g) })),
    ...texts.map((t): Source => ({ kind: "title", find: () => titleTextMatches(db, user, t) })),
  ];
  const order = u.intent === "request" ? [...titles, ...lyrics] : [...lyrics, ...titles];
  const out: Result[] = [];
  const seen = new Set<string>();
  for (const src of order) {
    if (out.length >= MAX_RESULTS) break;
    for (const s of await src.find()) {
      if (seen.has(s.id)) continue;
      seen.add(s.id);
      out.push({ ...s, match: src.kind });
      if (out.length >= MAX_RESULTS) break;
    }
  }
  return out;
}
