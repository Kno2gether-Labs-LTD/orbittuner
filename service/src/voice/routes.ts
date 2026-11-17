import { Hono, type Context } from "hono";
import type { AppEnv } from "../auth/devices";
import { LANGS } from "../ai/discover";
import { refundQuota, reserveQuota } from "../ai/searchQuota";
import { readBodyBytes } from "../lib/body";
import { ApiError } from "../lib/errors";
import { clientIpKey } from "../lib/ip";
import type { AuthUser } from "../auth/devices";
import type { Env } from "../env";
import { matchSongs, type Result } from "./match";
import { inworldConfigured, parseWav, transcribeForced, transcribeWithFallback, withTimeout } from "./transcribe";
import { understand, type Guess, type Hints } from "./understand";

/**
 * Sing a line (beta): POST /v1/voice?langs=hi,bn&artists=Arijit%20Singh with an audio/wav body.
 *
 * 1. Hints (comma-separated query values): `langs` ≤ 3 codes from the discover LANGS list,
 *    `artists` ≤ 5 names of ≤ 60 chars -> else 400 bad_hints. Audio: WAV 16 kHz mono PCM16,
 *    1-20 s, ≤ 700 KB -> else 400 bad_audio. Nothing is charged for a 400.
 * 2. One unit of the voice quota (searchQuota kind "voice": per plan, per IP, global breaker).
 * 3. Speech-to-text: providers of the `stt_order` setting (Workers AI Whisper, Inworld) in order,
 *    each falling through on error. All failing refunds the unit and answers 503 voice_unavailable; a
 *    transcript without letters or digits is 200 intent "none" (the unit is kept).
 * 4. One gateway LLM call (understand, 12 s deadline; falls back to the raw transcript), then
 *    catalogue matches over titles and lyrics with the /v1/search visibility rules.
 * 5. Bengali/Hindi second pass: when pass 1 heard words but found no catalogue match and a Bengali or Hindi hint
 *    (else a detected bn/hi) gives a language, the clip is transcribed again with that language
 *    forced ([transcribeForced]: Inworld whisper-large-v3 when keyed, else Workers AI) and steps
 *    4 run on it. The pass with matches is returned (pass 2 when both). Pass 2 never charges
 *    another unit, starts only within [PASS2_START_MS] and is cut off after [PASS2_BUDGET_MS].
 *
 * The audio is never logged, persisted or echoed.
 */
export const MAX_AUDIO_BYTES = 700 * 1024;
export const MIN_SECONDS = 1;
export const MAX_SECONDS = 20;
export const MAX_HINT_LANGS = 3;
export const MAX_HINT_ARTISTS = 5;
export const MAX_ARTIST_CHARS = 60;
/** Languages that get the forced second pass (sung Bengali/Hindi is often misheard on auto-detect). */
export const SECOND_PASS_LANGS = ["bn", "hi"] as const;
/** Pass 2 starts only when pass 1 finished within this, and is abandoned after [PASS2_BUDGET_MS]. */
export const PASS2_START_MS = 20_000;
export const PASS2_BUDGET_MS = 20_000;

type Reply = {
  intent: "lyrics" | "request" | "none"; transcript: string; language: string | null; romanized: string;
  guesses: Guess[]; webQuery: string | null; results: Result[];
};

/** The forced language for pass 2: the first bn/hi hint, else a detected bn/hi; null for anything else. */
export function secondPassLanguage(hints: Hints, detected: string | null): string | null {
  const isTarget = (l: string | null): l is string => !!l && (SECOND_PASS_LANGS as readonly string[]).includes(l);
  return hints.langs.find(isTarget) ?? (isTarget(detected) ? detected : null);
}

/** Understand + match one transcript (or the no-words reply when it has no letters or digits). */
async function answer(
  env: Env, user: AuthUser, hints: Hints, heard: { text: string; language: string | null },
): Promise<Reply> {
  if (!/[\p{L}\p{N}]/u.test(heard.text)) {
    return { intent: "none", transcript: heard.text, language: heard.language, romanized: "", guesses: [], webQuery: null, results: [] };
  }
  const u = await understand(env, heard.text, hints, heard.language);
  const results = await matchSongs(env.DB, user, u, heard.text);
  return {
    intent: u.intent, transcript: heard.text, language: u.language, romanized: u.romanized, guesses: u.guesses,
    webQuery: u.query, results,
  };
}

export const voiceApp = new Hono<AppEnv>();

const badHints = () => new ApiError(400, "bad_hints");
const badAudio = () => new ApiError(400, "bad_audio");

const list = (v: string | undefined) => [...new Set((v ?? "").split(",").map((s) => s.trim()).filter(Boolean))];

export function parseHints(c: Context<AppEnv>): Hints {
  const langs = list(c.req.query("langs"));
  if (langs.length > MAX_HINT_LANGS || !langs.every((l) => LANGS.has(l))) throw badHints();
  const artists = list(c.req.query("artists"));
  if (
    artists.length > MAX_HINT_ARTISTS ||
    artists.some((a) => a.length > MAX_ARTIST_CHARS || /[\u0000-\u001f\u007f]/.test(a))
  ) throw badHints();
  return { langs, artists };
}

voiceApp.post("/v1/voice", async (c) => {
  const hints = parseHints(c);
  let audio: Uint8Array;
  try {
    audio = await readBodyBytes(c, MAX_AUDIO_BYTES);
  } catch (e) {
    if (e instanceof ApiError && e.code === "too_large") throw badAudio();
    throw e;
  }
  const wav = parseWav(audio);
  if (!wav || wav.seconds < MIN_SECONDS || wav.seconds > MAX_SECONDS) throw badAudio();

  const started = Date.now();
  const user = c.var.user;
  const ipKey = clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown");
  const reservation = await reserveQuota(c.env, "voice", user, ipKey);
  let heard: { text: string; language: string | null };
  try {
    heard = await transcribeWithFallback(c.env, audio);
  } catch {
    console.log("voice: all stt providers failed");
    await refundQuota(c.env, reservation).catch(() => console.error("voice refund failed"));
    throw new ApiError(503, "voice_unavailable");
  }
  const first = await answer(c.env, user, hints, heard);
  // Silence (no words) never gets a second pass: only words that found nothing do.
  if (first.results.length > 0 || first.intent === "none") return c.json(first);

  // Pass 2: sung Bengali/Hindi again with the language forced. Pass 1's unit covers it.
  const lang = secondPassLanguage(hints, heard.language);
  if (!lang || Date.now() - started > PASS2_START_MS) return c.json(first);
  // Workers AI already detected this very language: forcing it on the same model changes nothing.
  if (heard.language === lang && !(await inworldConfigured(c.env))) return c.json(first);
  let second: Reply | null = null;
  try {
    second = await withTimeout((async () => {
      const again = await transcribeForced(c.env, audio, lang);
      if (again.text.trim() === heard.text.trim()) return null; // same words: same answer
      return answer(c.env, user, hints, { text: again.text, language: again.language ?? lang });
    })(), PASS2_BUDGET_MS);
  } catch {
    console.log("voice: second pass gave nothing");
  }
  return c.json(second !== null && second.results.length > 0 ? second : first);
});
