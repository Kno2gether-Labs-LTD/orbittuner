import type { Env } from "../env";
import { getSecret } from "../lib/secrets";

/** Workers AI speech-to-text model (binding AI). */
export const WHISPER_MODEL = "@cf/openai/whisper-large-v3-turbo";

const RATE = 16000;
const BYTES_PER_SECOND = RATE * 2; // mono PCM16

const ascii = (b: Uint8Array, off: number, s: string) =>
  off + s.length <= b.length && [...s].every((ch, i) => b[off + i] === ch.charCodeAt(0));

/**
 * Check a WAV file is RIFF/WAVE with a PCM (format 1), 16000 Hz, mono, 16-bit fmt chunk and a
 * data chunk; returns its duration, or null for anything else. Chunks are walked (word-aligned),
 * so LIST/fact chunks before the data are fine. A data size larger than the bytes present (a
 * streaming writer's placeholder) counts only the bytes present.
 */
export function parseWav(b: Uint8Array): { seconds: number } | null {
  if (b.length < 12 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WAVE")) return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  let fmtOk = false;
  for (let off = 12; off + 8 <= b.length;) {
    const size = v.getUint32(off + 4, true);
    const body = off + 8;
    if (ascii(b, off, "fmt ")) {
      if (size < 16 || body + 16 > b.length) return null;
      fmtOk = v.getUint16(body, true) === 1 && v.getUint16(body + 2, true) === 1 &&
        v.getUint32(body + 4, true) === RATE && v.getUint16(body + 14, true) === 16;
      if (!fmtOk) return null;
    } else if (ascii(b, off, "data")) {
      if (!fmtOk) return null;
      const bytes = Math.min(size, b.length - body);
      return { seconds: Math.floor(bytes / 2) * 2 / BYTES_PER_SECOND };
    }
    off = body + size + (size % 2);
  }
  return null;
}

function toBase64(b: Uint8Array): string {
  let s = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < b.length; i += CHUNK) s += String.fromCharCode(...b.subarray(i, i + CHUNK));
  return btoa(s);
}

/** Whisper's no-speech thresholds (as in openai/whisper transcribe()). */
export const NO_SPEECH_PROB = 0.6;
export const NO_SPEECH_LOGPROB = -1;

/** True when there is at least one segment and every one is no-speech by Whisper's rule. */
function allNoSpeech(segments: unknown): boolean {
  if (!Array.isArray(segments) || segments.length === 0) return false;
  return segments.every((g) => {
    const s = g as { no_speech_prob?: unknown; avg_logprob?: unknown } | null;
    return typeof s?.no_speech_prob === "number" && typeof s.avg_logprob === "number" &&
      s.no_speech_prob > NO_SPEECH_PROB && s.avg_logprob < NO_SPEECH_LOGPROB;
  });
}

/**
 * One Whisper run over a validated WAV clip, with voice activity detection on. Without `language` Whisper detects
 * it (the first pass never forces one); the Bengali/Hindi second pass ([transcribeForced]) passes one. Throws
 * when the model fails or returns no text field; an empty string is a valid (silent) result.
 * Whisper's own no-speech rule turns hallucinated text on silence ("Thank you.") into "": when
 * every segment has no_speech_prob > 0.6 AND avg_logprob < -1 the text is dropped. Without
 * segments the text is kept. The audio is never logged or kept.
 */
export async function transcribe(
  env: Pick<Env, "AI">, wav: Uint8Array, language?: string,
): Promise<{ text: string; language: string | null }> {
  const input: { audio: string; task: string; vad_filter: boolean; language?: string } = {
    audio: toBase64(wav), task: "transcribe", vad_filter: true,
  };
  if (language) input.language = language;
  const out = (await env.AI.run(WHISPER_MODEL, input)) as {
    text?: unknown; transcription_info?: { language?: unknown }; segments?: unknown;
  };
  if (typeof out?.text !== "string") throw new Error("whisper_bad_output");
  const lang = out.transcription_info?.language;
  return { text: allNoSpeech(out.segments) ? "" : out.text.trim(), language: typeof lang === "string" && lang ? lang.slice(0, 16) : null };
}

/** Speech-to-text providers, tried in the order of the `stt_order` setting. */
export type SttProvider = "workers-ai" | "inworld";
export const STT_PROVIDERS: readonly SttProvider[] = ["workers-ai", "inworld"];
export const DEFAULT_STT_ORDER = "workers-ai,inworld";
export const INWORLD_STT_URL = "https://api.inworld.ai/stt/v1/transcribe";
export const DEFAULT_INWORLD_STT_MODEL = "groq/whisper-large-v3";
export const STT_TIMEOUT_MS = 15_000;

/** A comma list of known provider ids, de-duplicated, in order; null when empty or anything is unknown. */
export function parseSttOrder(raw: string): SttProvider[] | null {
  const ids = [...new Set(raw.split(",").map((x) => x.trim()).filter(Boolean))];
  if (ids.length === 0 || !ids.every((x) => (STT_PROVIDERS as readonly string[]).includes(x))) return null;
  return ids as SttProvider[];
}

/** Whisper's usual hallucinations on silence, after normalisation (lower case, letters and digits only). */
const SILENCE_PHRASES = new Set(["thank you", "thanks for watching", "you", "bye"]);
const normalisePhrase = (t: string) => t.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();

async function inworldModel(env: Env): Promise<string> {
  const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'inworld_stt_model'").first<{ value: string }>();
  return row?.value?.trim() || DEFAULT_INWORLD_STT_MODEL;
}

/**
 * One Inworld speech-to-text call. Inworld gives no no-speech data, so a transcript that is empty or
 * only a known Whisper silence phrase counts as "". The language is unknown (null) unless `language`
 * was forced (then it is that). Throws on a missing key, a non-2xx answer, a timeout or an unreadable
 * body; messages are reason codes only.
 */
export async function transcribeInworld(
  env: Env, wav: Uint8Array, language?: string, timeoutMs = STT_TIMEOUT_MS,
): Promise<{ text: string; language: string | null }> {
  const key = await getSecret(env, "INWORLD_API_KEY");
  if (!key) throw new Error("no_key");
  const transcribeConfig: Record<string, unknown> = {
    modelId: await inworldModel(env), audioEncoding: "LINEAR16", sampleRateHertz: RATE, numberOfChannels: 1,
  };
  if (language) transcribeConfig.language = language;
  const res = await fetch(INWORLD_STT_URL, {
    method: "POST",
    headers: { Authorization: `Basic ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ transcribeConfig, audioData: { content: toBase64(wav) } }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`http_${res.status}`);
  let j: { transcription?: { transcript?: unknown } } | null;
  try {
    j = await res.json();
  } catch {
    throw new Error("bad_json");
  }
  const t = j?.transcription?.transcript;
  if (typeof t !== "string") throw new Error("bad_output");
  const text = t.trim();
  const n = normalisePhrase(text);
  return { text: n === "" || SILENCE_PHRASES.has(n) ? "" : text, language: language ?? null };
}

export function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout>;
  const limit = new Promise<never>((_, rej) => { timer = setTimeout(() => rej(new Error("timeout")), ms); });
  return Promise.race([p, limit]).finally(() => clearTimeout(timer));
}

/**
 * Transcribe with the providers of `stt_order` (default "workers-ai,inworld"): an error, non-2xx
 * answer or 15 s timeout falls through to the next. Throws only when every provider failed. Only
 * `stt provider <id> failed: <reason>` is logged; never the key, the audio or a response body.
 */
export async function transcribeWithFallback(
  env: Env, wav: Uint8Array,
): Promise<{ text: string; language: string | null }> {
  let order = parseSttOrder(DEFAULT_STT_ORDER)!;
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'stt_order'").first<{ value: string }>();
    order = parseSttOrder(row?.value ?? "") ?? order;
  } catch {
    /* settings unreadable: default order */
  }
  for (const id of order) {
    try {
      return id === "inworld"
        ? await transcribeInworld(env, wav)
        : await withTimeout(transcribe(env, wav), STT_TIMEOUT_MS);
    } catch (err) {
      console.log(`stt provider ${id} failed: ${reasonOf(err)}`);
    }
  }
  throw new Error("stt_unavailable");
}

const reasonOf = (err: unknown) =>
  err instanceof Error && /^(no_key|http_\d{3}|bad_json|bad_output|timeout|whisper_bad_output)$/.test(err.message) ? err.message
    : err instanceof Error && err.name === "TimeoutError" ? "timeout" : "error";

/** Per-provider limit of the second (language-forced) pass; the route also bounds the whole pass. */
export const FORCED_STT_TIMEOUT_MS = 10_000;

/**
 * The Bengali/Hindi second pass: one transcription with `language` forced. Inworld's full
 * whisper-large-v3 (stronger than turbo on low-resource languages) when its key is configured,
 * else (or when it fails) Workers AI with the language set. Throws when both fail. Logs only
 * `stt forced <id> failed: <reason>`.
 */
export async function transcribeForced(
  env: Env, wav: Uint8Array, language: string,
): Promise<{ text: string; language: string | null; provider: SttProvider }> {
  let key: string | undefined;
  try { key = await getSecret(env, "INWORLD_API_KEY"); } catch { key = undefined; }
  if (key) {
    try {
      return { ...(await transcribeInworld(env, wav, language, FORCED_STT_TIMEOUT_MS)), provider: "inworld" };
    } catch (err) {
      console.log(`stt forced inworld failed: ${reasonOf(err)}`);
    }
  }
  try {
    const r = await withTimeout(transcribe(env, wav, language), FORCED_STT_TIMEOUT_MS);
    return { text: r.text, language: r.language ?? language, provider: "workers-ai" };
  } catch (err) {
    console.log(`stt forced workers-ai failed: ${reasonOf(err)}`);
    throw new Error("stt_unavailable");
  }
}

/** True when Inworld can take the forced pass (its key is set). */
export async function inworldConfigured(env: Env): Promise<boolean> {
  try { return !!(await getSecret(env, "INWORLD_API_KEY")); } catch { return false; }
}
