import type { Env } from "../env";
import { loadLlm } from "../ai/lazy";

export type Guess = { title: string; artist: string | null };
export type Hints = { langs: string[]; artists: string[] };
export type Understanding = {
  intent: "lyrics" | "request";
  language: string | null;
  /** The transcript in plain Latin script (command words kept); equals the transcript on fallback. */
  romanized: string;
  guesses: Guess[];
  /** A short web-search query, or null. */
  query: string | null;
};

export const MAX_GUESSES = 5;
/** The understand step's own deadline: past it the raw transcript is used, as on an LLM failure. */
export const UNDERSTAND_TIMEOUT_MS = 12_000;
const MAX_FIELD = 200;
const MAX_QUERY = 120;

const SYSTEM = `You help a guitar chord app find a song from a short voice clip that was transcribed by Whisper.
The clip is one of:
(a) a command, e.g. "find the chords for Tumi Robe Nirobe by Rabindranath Tagore", "search this song ...", "play ...";
(b) just a song title said aloud, e.g. "Tumi Robe Nirobe";
(c) a line of a song being sung.
Reply with one JSON object only:
{"intent": "request" | "lyrics", "language": "<ISO 639-1 code of the song language>", "romanized": "<transcript>", "guesses": [{"title": "<song title>", "artist": "<artist or null>"}], "query": "<web search>"}
Rules:
- intent "request" for (a) and (b); intent "lyrics" for (c). A bare phrase that could be a title or a first line is "request".
- romanized: the whole transcript in plain Latin script as it is commonly written on chord and lyric sites (e.g. Hindi "tum hi ho", Bengali "tumi robe nirobe"), lower case, no diacritics, no punctuation. Keep English words as they are.
- Sung Bengali and Hindi: the transcript is often in Bengali (বাংলা) or Devanagari script, and singing makes Whisper mishear: stretched vowels ("হোওও", "होओ"), dropped or doubled syllables, near-sounding wrong words, and sometimes the wrong script (Hindi words written in Bengali letters or the other way round). Romanize the words the singer most likely sang, spelled the way Bengali and Hindi chord sites write them: Bengali "ami", "tumi", "bhalobashi", "amar", "shona", "mon", "chokhe"; Hindi "tum", "hai", "mera", "dil", "pyaar", "zindagi". Collapse stretched vowels; do not translate.
- For sung lines, think of well-known Bengali and Hindi songs (film songs, Rabindra Sangeet, Nazrul Geeti, adhunik, band and folk songs) whose lyrics contain the line or something close to it, even when the transcript is partial or a few words are misheard; put the best of them in guesses with their usual romanized titles.
- guesses: at most ${MAX_GUESSES} likely songs, best first. For commands, strip command words ("find", "search", "play", "the chords for", "song", "by ...") and put the title and artist in separate fields. Use the user's language and artist hints only to break ties, never to invent a match. Empty list when unsure.
- query: a short web search for the song (title and artist when known, else the key words of the line), without the word "chords".`;

/** Fallback when the gateway is unavailable or replies with something unusable. */
export const fallbackUnderstanding = (transcript: string, language: string | null = null): Understanding => ({
  intent: "lyrics",
  language,
  romanized: transcript,
  guesses: [],
  query: transcript.slice(0, MAX_QUERY) || null,
});

const str = (v: unknown, max: number): string | null =>
  typeof v === "string" && v.trim() ? v.trim().replace(/\s+/g, " ").slice(0, max) : null;

/** Validate the model's JSON; null when the shape is unusable. */
export function parseUnderstanding(text: string, transcript: string): Understanding | null {
  let j: unknown;
  try {
    j = JSON.parse(text.trim().replace(/^```(?:json)?\s*|\s*```$/g, ""));
  } catch {
    return null;
  }
  if (typeof j !== "object" || j === null || Array.isArray(j)) return null;
  const o = j as Record<string, unknown>;
  if (o.intent !== "lyrics" && o.intent !== "request") return null;
  const guesses: Guess[] = [];
  if (Array.isArray(o.guesses)) {
    for (const g of o.guesses) {
      if (guesses.length >= MAX_GUESSES) break;
      const title = str((g as Record<string, unknown> | null)?.title, MAX_FIELD);
      if (!title) continue;
      guesses.push({ title, artist: str((g as Record<string, unknown>).artist, MAX_FIELD) });
    }
  }
  const lang = str(o.language, 16);
  return {
    intent: o.intent,
    language: lang && /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(lang) ? lang : null,
    romanized: str(o.romanized, 500) ?? transcript,
    guesses,
    query: str(o.query, MAX_QUERY),
  };
}

/**
 * One json-mode LLM call through the gateway: intent, romanized text, title/artist guesses and a
 * web query. Never throws: a gateway failure, an unusable reply or no answer within `timeoutMs`
 * (default [UNDERSTAND_TIMEOUT_MS]) returns the fallback (intent "lyrics", romanized = transcript,
 * no guesses).
 */
export async function understand(
  env: Env, transcript: string, hints: Hints, whisperLanguage: string | null = null,
  timeoutMs = UNDERSTAND_TIMEOUT_MS,
): Promise<Understanding> {
  const user = [
    `Transcript: ${JSON.stringify(transcript)}`,
    whisperLanguage ? `Whisper detected language: ${whisperLanguage}` : null,
    hints.langs.length ? `User's usual song languages (tie-breaker only): ${hints.langs.join(", ")}` : null,
    hints.artists.length ? `User's usual artists (tie-breaker only): ${hints.artists.join("; ")}` : null,
  ].filter(Boolean).join("\n");
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<"timeout">((resolve) => { timer = setTimeout(() => resolve("timeout"), timeoutMs); });
  try {
    // Loaded lazily like the other AI modules (see ai/lazy.ts).
    const call = loadLlm().then(({ complete }) => complete(env, { system: SYSTEM, user, json: true, maxTokens: 600 }));
    call.catch(() => {}); // a late failure after the deadline is not an unhandled rejection
    const r = await Promise.race([call, deadline]);
    if (r === "timeout") {
      console.log("voice understand: timed out");
      return fallbackUnderstanding(transcript, whisperLanguage);
    }
    const u = parseUnderstanding(r.text, transcript);
    if (u) return { ...u, language: u.language ?? whisperLanguage };
    console.log("voice understand: unusable reply");
  } catch {
    console.log("voice understand: gateway unavailable");
  } finally {
    clearTimeout(timer);
  }
  return fallbackUnderstanding(transcript, whisperLanguage);
}
