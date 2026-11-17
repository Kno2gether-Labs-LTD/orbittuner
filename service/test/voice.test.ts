import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Env } from "../src/env";
import { ApiError } from "../src/lib/errors";

vi.mock("../src/ai/llm", async (orig) => ({ ...(await orig<typeof import("../src/ai/llm")>()), complete: vi.fn() }));
import { complete } from "../src/ai/llm";
import worker from "../src/index";
import { upsertSong } from "../src/songs/repo";
import { parseWav } from "../src/voice/transcribe";
import { UNDERSTAND_TIMEOUT_MS, understand } from "../src/voice/understand";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

const llm = vi.mocked(complete);
const whisper = vi.fn();
let inworldKey: string | undefined;
const testEnv = () => ({ ...(env as unknown as Env), AI: { run: whisper }, INWORLD_API_KEY: inworldKey }) as unknown as Env;
const INWORLD_URL = "https://api.inworld.ai/stt/v1/transcribe";
const inworldFetch = vi.fn();
const inworldSays = (transcript: string) =>
  inworldFetch.mockResolvedValueOnce(new Response(JSON.stringify({ transcription: { transcript } }), { status: 200 }));
const setOrder = (v: string) =>
  env.DB.prepare("INSERT INTO settings (key, value) VALUES ('stt_order', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(v).run();
const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;

/** A WAV file: RIFF/WAVE, a fmt chunk, then `seconds` of silence (or `dataBytes` raw bytes). */
function wav(seconds: number, o: { rate?: number; ch?: number; bits?: number; fmt?: number; dataBytes?: number } = {}) {
  const rate = o.rate ?? 16000, ch = o.ch ?? 1, bits = o.bits ?? 16, fmt = o.fmt ?? 1;
  const data = o.dataBytes ?? Math.round(seconds * rate * ch * (bits / 8));
  const buf = new Uint8Array(44 + data);
  const v = new DataView(buf.buffer);
  const ascii = (off: number, s: string) => { for (let i = 0; i < s.length; i++) buf[off + i] = s.charCodeAt(i); };
  ascii(0, "RIFF"); v.setUint32(4, 36 + data, true); ascii(8, "WAVE");
  ascii(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, fmt, true); v.setUint16(22, ch, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * ch * (bits / 8), true); v.setUint16(32, ch * (bits / 8), true);
  v.setUint16(34, bits, true);
  ascii(36, "data"); v.setUint32(40, data, true);
  for (let i = 44; i < buf.length; i++) buf[i] = i % 251; // non-silent filler, so the base64 check is meaningful
  return buf;
}

function voice(who: { headers: Record<string, string> }, body: Uint8Array, query = "", ip = "203.0.113.9") {
  return worker.fetch!(
    new Request(`${ORIGIN}/v1/voice${query}`, {
      method: "POST",
      headers: { ...who.headers, "content-type": "audio/wav", "cf-connecting-ip": ip },
      body,
    }) as any,
    testEnv(),
    ctx,
  );
}

const heard = (text: string, language = "hi") => whisper.mockResolvedValueOnce({ text, transcription_info: { language } });
const understood = (u: unknown) =>
  llm.mockResolvedValueOnce({ text: JSON.stringify(u), providerId: "p1", model: "m1", ms: 3 });

const today = () => new Date().toISOString().slice(0, 10);
const counter = async (scope: string, key: string) =>
  (await env.DB.prepare("SELECT COALESCE(SUM(count), 0) AS n FROM search_usage WHERE scope = ? AND key = ? AND day = ?")
    .bind(scope, key, today()).first<{ n: number }>())!.n;

let owner: Awaited<ReturnType<typeof makeUser>>;
let anon: Awaited<ReturnType<typeof makeUser>>;
let other: Awaited<ReturnType<typeof makeUser>>;

async function song(title: string, artist: string, lyrics: string, o: { status?: "published" | "review" | "hidden"; by?: string } = {}) {
  return upsertSong(env.DB, {
    chordpro: `{title: ${title}}\n{artist: ${artist}}\n\n[G]${lyrics}\n`,
    source: "owner", status: o.status ?? "published", createdBy: o.by ?? owner.id,
  });
}

beforeEach(async () => {
  llm.mockReset();
  whisper.mockReset();
  inworldFetch.mockReset();
  inworldKey = "inworld-test-key-abc";
  vi.stubGlobal("fetch", inworldFetch);
  await env.DB.prepare("DELETE FROM settings WHERE key IN ('stt_order','inworld_stt_model')").run();
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"), env.DB.prepare("DELETE FROM search_usage"),
    env.DB.prepare("UPDATE settings SET value='3' WHERE key='voice_daily_anon'"),
    env.DB.prepare("UPDATE settings SET value='10' WHERE key='voice_daily_free'"),
    env.DB.prepare("UPDATE settings SET value='50' WHERE key='voice_daily_plus'"),
    env.DB.prepare("UPDATE settings SET value='40' WHERE key='voice_daily_per_ip'"),
    env.DB.prepare("UPDATE settings SET value='1000' WHERE key='voice_daily_global'"),
  ]);
  owner = await makeUser("owner", "o");
  anon = await makeUser("user", "a");
  other = await makeUser("user", "b");
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("parseWav", () => {
  it("reads the duration of a 16 kHz mono PCM16 file and rejects other formats", () => {
    expect(parseWav(wav(2))).toEqual({ seconds: 2 });
    expect(parseWav(new TextEncoder().encode("not a wav file at all, just some text bytes here"))).toBeNull();
    expect(parseWav(wav(2, { rate: 44100 }))).toBeNull();
    expect(parseWav(wav(2, { ch: 2 }))).toBeNull();
    expect(parseWav(wav(2, { bits: 8 }))).toBeNull();
    expect(parseWav(wav(2, { fmt: 3, bits: 32 }))).toBeNull();
  });
});

describe("POST /v1/voice", () => {
  it("rejects non-WAV, oversized, too short and too long audio with 400 bad_audio, charging nothing", async () => {
    const big = wav(0, { dataBytes: 700 * 1024 }); // 700 KB of data + header > 700 KB
    for (const body of [
      new TextEncoder().encode("hello this is not audio, it is just a short text body for the test"),
      new Uint8Array(0), big, wav(0.5), wav(21), wav(2, { rate: 44100 }), wav(2, { ch: 2 }), wav(2, { bits: 8 }),
    ]) {
      const r = await voice(anon, body);
      expect(r.status).toBe(400);
      expect(await r.json()).toEqual({ error: "bad_audio" });
    }
    expect(whisper).not.toHaveBeenCalled();
    expect(await counter("voice_user", anon.id)).toBe(0);
  });

  it("rejects bad hints with 400 bad_hints", async () => {
    for (const q of [
      "?langs=fr", "?langs=hi,bn,ta,te", "?langs=english!",
      `?artists=${["a", "b", "c", "d", "e", "f"].join(",")}`, `?artists=${"x".repeat(61)}`,
    ]) {
      const r = await voice(anon, wav(2), q);
      expect(r.status, q).toBe(400);
      expect(await r.json()).toEqual({ error: "bad_hints" });
    }
    expect(whisper).not.toHaveBeenCalled();
  });

  it("sends base64 audio to Whisper, and never forces a language on the first pass, even with exactly one hint language", async () => {
    inworldKey = undefined; // so a second pass (if any) is a second Workers AI call
    const body = wav(1.5);
    heard("", "en");
    await voice(anon, body, "?langs=en&artists=Meera%20Sen");
    expect(whisper).toHaveBeenCalledTimes(1);
    expect(whisper.mock.calls[0][0]).toBe("@cf/openai/whisper-large-v3-turbo");
    const input = whisper.mock.calls[0][1];
    expect(input.task).toBe("transcribe");
    expect("language" in input).toBe(false);
    expect(Uint8Array.from(atob(input.audio), (ch) => ch.charCodeAt(0))).toEqual(body);
    heard("", "en");
    await voice(anon, body, "?langs=hi,bn");
    expect("language" in whisper.mock.calls[1][1]).toBe(false);
  });

  it("happy path lyrics: a Devanagari transcript is romanized and finds the Latin-script lyric row first", async () => {
    const tum = await song("Dil Se Gao", "Meera Sen", "Hum saath chalenge har pal, dil se gao ab dil se gao");
    await song("Amazing Grace", "Trad", "Amazing grace how sweet the sound");
    heard("दिल से गाओ, अब दिल से गाओ");
    understood({
      intent: "lyrics", language: "hi", romanized: "dil se gao ab dil se gao",
      guesses: [{ title: "Dil Se Gao", artist: "Meera Sen" }], query: "dil se gao meera sen",
    });
    const r = await voice(anon, wav(4), "?langs=hi&artists=Meera%20Sen");
    expect(r.status).toBe(200);
    const j = (await r.json()) as any;
    expect(j.intent).toBe("lyrics");
    expect(j.transcript).toBe("दिल से गाओ, अब दिल से गाओ");
    expect(j.language).toBe("hi");
    expect(j.guesses).toEqual([{ title: "Dil Se Gao", artist: "Meera Sen" }]);
    expect(j.webQuery).toBe("dil se gao meera sen");
    expect(j.romanized).toBe("dil se gao ab dil se gao");
    expect(j.results[0]).toMatchObject({ id: tum.id, title: "Dil Se Gao", match: "lyrics" });
    expect(j.results).toHaveLength(1);
    const prompt = llm.mock.calls[0][1];
    expect(prompt.json).toBe(true);
    expect(prompt.user).toContain("दिल से गाओ");
    expect(prompt.user).toContain("Meera Sen");
    expect(await counter("voice_user", anon.id)).toBe(1);
  });

  it("request intent: 'play amazing grace' finds the title", async () => {
    const ag = await song("Amazing Grace", "Trad", "How sweet the sound that saved a wretch like me");
    heard("Play Amazing Grace.", "en");
    understood({ intent: "request", language: "en", romanized: "play amazing grace", guesses: [{ title: "Amazing Grace", artist: null }], query: "amazing grace" });
    const j = (await (await voice(anon, wav(2))).json()) as any;
    expect(j.intent).toBe("request");
    expect(j.results[0]).toMatchObject({ id: ag.id, match: "title" });
  });

  it("command phrasing: title + artist guess, the artist breaks a tie between two songs with the same title", async () => {
    const cover = await song("Tumi Robe Nirobe", "Some Band", "A cover with other words entirely");
    const tagore = await song("Tumi Robe Nirobe", "Rabindranath Tagore", "Hridoye momo, tumi robe nirobe");
    heard("Find the chords for Tumi Robe Nirobe by Rabindranath Tagore", "en");
    understood({
      intent: "request", language: "bn", romanized: "find the chords for tumi robe nirobe by rabindranath tagore",
      guesses: [{ title: "Tumi Robe Nirobe", artist: "Rabindranath Tagore" }], query: "tumi robe nirobe rabindranath tagore",
    });
    const j = (await (await voice(anon, wav(3))).json()) as any;
    expect(j.intent).toBe("request");
    expect(j.results.map((x: any) => x.id).slice(0, 2)).toEqual([tagore.id, cover.id]);
    expect(j.results[0].match).toBe("title");
  });

  it("bare title: title match first, a lyric-line match also returned", async () => {
    const line = await song("Shesh Gaan", "Someone", "tumi robe nirobe hridoye momo");
    const title = await song("Tumi Robe Nirobe", "Rabindranath Tagore", "Nibiro nibhrito purnimanishithini shamo");
    heard("Tumi robe nirobe", "bn");
    understood({ intent: "request", language: "bn", romanized: "tumi robe nirobe", guesses: [{ title: "Tumi Robe Nirobe", artist: "Rabindranath Tagore" }], query: "tumi robe nirobe" });
    const j = (await (await voice(anon, wav(2))).json()) as any;
    expect(j.results.map((x: any) => [x.id, x.match])).toEqual([[title.id, "title"], [line.id, "lyrics"]]);
  });

  it("LLM failure: 200 with title and lyric results on the raw transcript", async () => {
    const line = await song("Shesh Gaan", "Someone", "tumi robe nirobe hridoye momo");
    const title = await song("Tumi Robe Nirobe", "Rabindranath Tagore", "Nibiro nibhrito purnimanishithini shamo");
    heard("tumi robe nirobe", "bn");
    llm.mockRejectedValueOnce(new ApiError(502, "ai_unavailable"));
    const r = await voice(anon, wav(2));
    expect(r.status).toBe(200);
    const j = (await r.json()) as any;
    expect(j.intent).toBe("lyrics");
    expect(j.guesses).toEqual([]);
    expect(j.language).toBe("bn");
    expect(j.romanized).toBe("tumi robe nirobe");
    expect(j.results.map((x: any) => [x.id, x.match])).toEqual([[line.id, "lyrics"], [title.id, "title"]]);
  });

  it("LLM returning garbage falls back the same way", async () => {
    const line = await song("Shesh Gaan", "Someone", "tumi robe nirobe hridoye momo");
    heard("tumi robe nirobe hridoye", "bn");
    llm.mockResolvedValueOnce({ text: "not json", providerId: "p1", model: "m1", ms: 1 });
    const j = (await (await voice(anon, wav(2))).json()) as any;
    expect(j.results[0]).toMatchObject({ id: line.id, match: "lyrics" });
  });

  it("Whisper failure: 503 voice_unavailable and the quota is refunded", async () => {
    whisper.mockRejectedValueOnce(new Error("model down"));
    const r = await voice(anon, wav(2));
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "voice_unavailable" });
    expect(await counter("voice_user", anon.id)).toBe(0);
    expect(await counter("voice_ip", "203.0.113.9")).toBe(0);
    expect(await counter("voice_global", "*")).toBe(0);
  });

  it("empty transcript: 200 intent none, no LLM call, the quota is kept", async () => {
    heard("  ...  ", "en");
    const r = await voice(anon, wav(2));
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ intent: "none", transcript: "...", language: "en", romanized: "", guesses: [], webQuery: null, results: [] });
    expect(llm).not.toHaveBeenCalled();
    expect(await counter("voice_user", anon.id)).toBe(1);
  });

  it("silence: Whisper's no-speech segments ('Thank you.') count as no words, the quota is kept", async () => {
    await song("Thank You", "Dido", "My tea's gone cold, I'm wondering why");
    whisper.mockResolvedValueOnce({
      text: "Thank you.", transcription_info: { language: "en" },
      segments: [{ no_speech_prob: 0.9, avg_logprob: -1.2 }, { no_speech_prob: 0.7, avg_logprob: -1.5 }],
    });
    const r = await voice(anon, wav(2));
    expect(r.status).toBe(200);
    expect(await r.json()).toMatchObject({ intent: "none", transcript: "", guesses: [], webQuery: null, results: [] });
    expect(whisper.mock.calls[0][1].vad_filter).toBe(true);
    expect(llm).not.toHaveBeenCalled();
    expect(await counter("voice_user", anon.id)).toBe(1);
  });

  it("keeps the text when any segment is confident speech", async () => {
    const ty = await song("Thank You", "Dido", "My tea's gone cold, I'm wondering why");
    whisper.mockResolvedValueOnce({
      text: "Thank you", transcription_info: { language: "en" },
      segments: [{ no_speech_prob: 0.9, avg_logprob: -1.2 }, { no_speech_prob: 0.1, avg_logprob: -0.3 }],
    });
    understood({ intent: "request", language: "en", romanized: "thank you", guesses: [{ title: "Thank You", artist: null }], query: "thank you" });
    const j = (await (await voice(anon, wav(2))).json()) as any;
    expect(j.intent).toBe("request");
    expect(j.transcript).toBe("Thank you");
    expect(j.results[0]).toMatchObject({ id: ty.id, match: "title" });
  });

  it("own quota: 4th anonymous call is 429, owner unlimited, search counters untouched", async () => {
    for (let i = 0; i < 3; i++) {
      heard("", "en");
      expect((await voice(anon, wav(1))).status).toBe(200);
    }
    const r = await voice(anon, wav(1));
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "voice_limit_reached", limit: 3, plan: "anonymous" });
    expect(whisper).toHaveBeenCalledTimes(3);
    for (let i = 0; i < 5; i++) {
      heard("", "en");
      expect((await voice(owner, wav(1))).status).toBe(200);
    }
    for (const [scope, key] of [["user", anon.id], ["ip", "203.0.113.9"], ["global", "*"]]) {
      expect(await counter(scope, key)).toBe(0);
    }
    expect(await counter("voice_user", anon.id)).toBe(3);
    expect(await counter("voice_user", owner.id)).toBe(0);
  });

  it("global breaker: 503 voice_unavailable", async () => {
    await env.DB.prepare("UPDATE settings SET value='0' WHERE key='voice_daily_global'").run();
    const r = await voice(owner, wav(1));
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "voice_unavailable" });
    expect(whisper).not.toHaveBeenCalled();
  });

  it("never returns review or hidden songs to non-owners", async () => {
    await song("Hidden Song", "X", "dil se gao ab dil se gao", { status: "hidden", by: other.id });
    const review = await song("Review Song", "X", "dil se gao ab dil se gao mere", { status: "review", by: other.id });
    const ask = () => {
      heard("dil se gao ab dil se gao", "hi");
      understood({ intent: "lyrics", language: "hi", romanized: "dil se gao ab dil se gao", guesses: [{ title: "Review Song", artist: "X" }], query: "x" });
    };
    ask();
    expect(((await (await voice(anon, wav(2))).json()) as any).results).toEqual([]);
    ask();
    const mine = (await (await voice(other, wav(2), "", "203.0.113.10")).json()) as any;
    expect(mine.results.map((x: any) => x.id)).toContain(review.id);
  });

  describe("Inworld speech-to-text fallback", () => {
    const refunded = async () => {
      expect(await counter("voice_user", anon.id)).toBe(0);
      expect(await counter("voice_ip", "203.0.113.9")).toBe(0);
      expect(await counter("voice_global", "*")).toBe(0);
    };

    it("Workers AI throws: Inworld is used, 200 with matches, request shape and auth header as specified", async () => {
      const ag = await song("Amazing Grace", "Trad", "How sweet the sound that saved a wretch like me");
      whisper.mockRejectedValueOnce(new Error("model down"));
      inworldSays("Play Amazing Grace");
      understood({ intent: "request", language: "en", romanized: "play amazing grace", guesses: [{ title: "Amazing Grace", artist: null }], query: "amazing grace" });
      const body = wav(2);
      const r = await voice(anon, body, "?langs=en");
      expect(r.status).toBe(200);
      const j = (await r.json()) as any;
      expect(j.transcript).toBe("Play Amazing Grace");
      expect(j.results[0]).toMatchObject({ id: ag.id, match: "title" });
      expect(await counter("voice_user", anon.id)).toBe(1);

      expect(inworldFetch).toHaveBeenCalledTimes(1);
      const [url, init] = inworldFetch.mock.calls[0];
      expect(url).toBe(INWORLD_URL);
      expect(init.method).toBe("POST");
      expect(init.headers.Authorization).toBe("Basic inworld-test-key-abc");
      expect(init.headers["Content-Type"]).toBe("application/json");
      const sent = JSON.parse(init.body);
      expect(sent.transcribeConfig).toEqual({
        modelId: "groq/whisper-large-v3", audioEncoding: "LINEAR16", sampleRateHertz: 16000, numberOfChannels: 1,
      });
      expect(Uint8Array.from(atob(sent.audioData.content), (ch) => ch.charCodeAt(0))).toEqual(body);
    });

    it("never sends a language, even with exactly one hint language; model id overridable by inworld_stt_model", async () => {
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('inworld_stt_model', 'groq/whisper-large-v3-turbo')").run();
      await setOrder("inworld");
      inworldSays("");
      await voice(anon, wav(2), "?langs=bn");
      const sent = JSON.parse(inworldFetch.mock.calls[0][1].body);
      expect(sent.transcribeConfig.language).toBeUndefined();
      expect("language" in sent.transcribeConfig).toBe(false);
      expect(sent.transcribeConfig.modelId).toBe("groq/whisper-large-v3-turbo");
    });

    it("both providers fail: 503 voice_unavailable and the quota is refunded", async () => {
      whisper.mockRejectedValueOnce(new Error("down"));
      inworldFetch.mockResolvedValueOnce(new Response("nope", { status: 500 }));
      const r = await voice(anon, wav(2));
      expect(r.status).toBe(503);
      expect(await r.json()).toEqual({ error: "voice_unavailable" });
      await refunded();
    });

    it("an Inworld network error falls through too (all fail: 503, refunded)", async () => {
      whisper.mockRejectedValueOnce(new Error("down"));
      inworldFetch.mockRejectedValueOnce(new TypeError("network"));
      expect((await voice(anon, wav(2))).status).toBe(503);
      await refunded();
    });

    it("stt_order = inworld skips Workers AI", async () => {
      await setOrder("inworld");
      inworldSays("");
      const r = await voice(anon, wav(2));
      expect(r.status).toBe(200);
      expect(whisper).not.toHaveBeenCalled();
      expect(inworldFetch).toHaveBeenCalledTimes(1);
    });

    it("stt_order = inworld,workers-ai tries Inworld first; Workers AI takes over on failure", async () => {
      await setOrder("inworld,workers-ai");
      inworldFetch.mockResolvedValueOnce(new Response("{}", { status: 503 }));
      heard("", "en");
      expect((await voice(anon, wav(2))).status).toBe(200);
      expect(inworldFetch).toHaveBeenCalledTimes(1);
      expect(whisper).toHaveBeenCalledTimes(1);
    });

    it("a garbage stt_order falls back to the default order", async () => {
      await setOrder("whatever,,nope");
      heard("", "en");
      expect((await voice(anon, wav(2))).status).toBe(200);
      expect(whisper).toHaveBeenCalledTimes(1);
      expect(inworldFetch).not.toHaveBeenCalled();
    });

    it("Workers AI succeeding means Inworld is never called", async () => {
      heard("", "en");
      await voice(anon, wav(2));
      expect(inworldFetch).not.toHaveBeenCalled();
    });

    it("Inworld silence phrases ('Thank you.') are no words: intent none, quota kept, no LLM call", async () => {
      await setOrder("inworld");
      for (const t of ["Thank you.", "  thanks for watching! ", "You", ".", "Bye.", ""]) {
        inworldFetch.mockReset();
        inworldSays(t);
        const r = await voice(owner, wav(2));
        expect(r.status, t).toBe(200);
        expect(await r.json(), t).toMatchObject({ intent: "none", transcript: "", results: [] });
      }
      expect(llm).not.toHaveBeenCalled();
    });

    it("the silence rule is Inworld-only: Workers AI text 'Thank you' without segments is kept", async () => {
      const ty = await song("Thank You", "Dido", "My tea's gone cold, I'm wondering why");
      heard("Thank you", "en");
      understood({ intent: "request", language: "en", romanized: "thank you", guesses: [{ title: "Thank You", artist: null }], query: "thank you" });
      const j = (await (await voice(anon, wav(2))).json()) as any;
      expect(j.results[0]).toMatchObject({ id: ty.id });
    });

    it("a real Inworld phrase that merely contains a silence word is kept", async () => {
      await setOrder("inworld");
      await song("Thank You", "Dido", "My tea's gone cold, I'm wondering why");
      inworldSays("thank you for the music");
      understood({ intent: "lyrics", language: "en", romanized: "thank you for the music", guesses: [], query: "thank you for the music" });
      const j = (await (await voice(anon, wav(2))).json()) as any;
      expect(j.transcript).toBe("thank you for the music");
    });

    it("key missing: Inworld is skipped without a request (Workers AI failing then gives 503, refunded)", async () => {
      inworldKey = undefined;
      whisper.mockRejectedValueOnce(new Error("down"));
      expect((await voice(anon, wav(2))).status).toBe(503);
      expect(inworldFetch).not.toHaveBeenCalled();
      await refunded();
    });

    it("never logs the key, the audio or the provider's body", async () => {
      const spy = vi.spyOn(console, "log").mockImplementation(() => {});
      const err = vi.spyOn(console, "error").mockImplementation(() => {});
      whisper.mockRejectedValueOnce(new Error("secret-detail"));
      inworldFetch.mockResolvedValueOnce(new Response("body-secret-text", { status: 401 }));
      await voice(anon, wav(2));
      const logged = [...spy.mock.calls, ...err.mock.calls].flat().join("\n");
      expect(logged).toContain("stt provider workers-ai failed");
      expect(logged).toContain("stt provider inworld failed");
      for (const bad of ["inworld-test-key-abc", "secret-detail", "body-secret-text"]) expect(logged).not.toContain(bad);
    });
  });
});

describe("understand deadline", () => {
  it("is about 12 s", () => {
    expect(UNDERSTAND_TIMEOUT_MS).toBe(12_000);
  });

  it("a gateway that never answers falls back to the raw transcript once the deadline passes", async () => {
    llm.mockReturnValueOnce(new Promise(() => {}) as any);
    const t = Date.now();
    const u = await understand(testEnv(), "tumi robe nirobe", { langs: ["bn"], artists: [] }, "bn", 30);
    expect(Date.now() - t).toBeLessThan(2_000);
    expect(u).toEqual({ intent: "lyrics", language: "bn", romanized: "tumi robe nirobe", guesses: [], query: "tumi robe nirobe" });
  });

  it("a reply within the deadline is used", async () => {
    understood({ intent: "request", language: "bn", romanized: "tumi robe nirobe", guesses: [], query: "tumi robe nirobe" });
    const u = await understand(testEnv(), "তুমি রবে নীরবে", { langs: [], artists: [] }, "bn", 5_000);
    expect(u.intent).toBe("request");
    expect(u.romanized).toBe("tumi robe nirobe");
  });

  it("the prompt asks for Bengali/Devanagari sung lyrics to be romanized like chord sites and well-known songs guessed", async () => {
    understood({ intent: "lyrics", language: "bn", romanized: "x", guesses: [], query: "x" });
    await understand(testEnv(), "আমি তোমায় ভালোবাসি", { langs: ["bn"], artists: [] }, "bn");
    const system = llm.mock.calls[0][1].system;
    expect(system).toMatch(/Bengali .*Devanagari script/);
    expect(system).toContain("chord sites");
    expect(system).toMatch(/well-known Bengali and Hindi songs/);
    expect(system).toMatch(/misheard/);
  });
});

describe("Bengali/Hindi second pass", () => {
  const bengaliSong = () => song("Ami Tomake Bhalobashi", "Someone", "ami tomake bhalobashi shudhu tomake");
  const inworldCalls = () => inworldFetch.mock.calls.map((c) => JSON.parse(c[1].body).transcribeConfig);

  it("pass 1 finds nothing, a bn hint: Inworld large-v3 runs with bn forced and its matches are returned, one unit charged", async () => {
    const s = await bengaliSong();
    heard("I'm a tomato bottle bashi", "en");
    understood({ intent: "lyrics", language: "en", romanized: "im a tomato bottle bashi", guesses: [], query: "tomato bottle" });
    inworldSays("আমি তোমাকে ভালোবাসি");
    understood({ intent: "lyrics", language: "bn", romanized: "ami tomake bhalobashi", guesses: [], query: "ami tomake bhalobashi" });
    const r = await voice(anon, wav(4), "?langs=bn,hi");
    expect(r.status).toBe(200);
    const j = (await r.json()) as any;
    expect(j.transcript).toBe("আমি তোমাকে ভালোবাসি");
    expect(j.romanized).toBe("ami tomake bhalobashi");
    expect(j.language).toBe("bn");
    expect(j.results[0]).toMatchObject({ id: s.id, match: "lyrics" });
    expect(whisper).toHaveBeenCalledTimes(1);
    expect(inworldCalls()).toEqual([expect.objectContaining({ modelId: "groq/whisper-large-v3", language: "bn" })]);
    expect(llm).toHaveBeenCalledTimes(2);
    expect(llm.mock.calls[1][1].user).toContain("আমি তোমাকে ভালোবাসি");
    expect(await counter("voice_user", anon.id)).toBe(1);
    expect(await counter("voice_ip", "203.0.113.9")).toBe(1);
    expect(await counter("voice_global", "*")).toBe(1);
  });

  it("the top hint among bn/hi is forced: hi before bn wins", async () => {
    heard("something else", "en");
    understood({ intent: "lyrics", language: "en", romanized: "something else", guesses: [], query: null });
    inworldSays("");
    await voice(anon, wav(3), "?langs=en,hi,bn");
    expect(inworldCalls()[0].language).toBe("hi");
  });

  it("no Inworld key: Workers AI runs again with the language set", async () => {
    inworldKey = undefined;
    const s = await bengaliSong();
    heard("I'm a tomato bottle bashi", "en");
    understood({ intent: "lyrics", language: "en", romanized: "im a tomato bottle bashi", guesses: [], query: null });
    heard("আমি তোমাকে ভালোবাসি", "bn");
    understood({ intent: "lyrics", language: "bn", romanized: "ami tomake bhalobashi", guesses: [], query: null });
    const j = (await (await voice(anon, wav(4), "?langs=bn")).json()) as any;
    expect(whisper).toHaveBeenCalledTimes(2);
    expect("language" in whisper.mock.calls[0][1]).toBe(false);
    expect(whisper.mock.calls[1][1].language).toBe("bn");
    expect(inworldFetch).not.toHaveBeenCalled();
    expect(j.results[0]).toMatchObject({ id: s.id });
    expect(await counter("voice_user", anon.id)).toBe(1);
  });

  it("Inworld failing in pass 2 falls back to Workers AI with the language forced", async () => {
    const s = await bengaliSong();
    heard("tomato", "en");
    understood({ intent: "lyrics", language: "en", romanized: "tomato", guesses: [], query: null });
    inworldFetch.mockResolvedValueOnce(new Response("x", { status: 500 }));
    heard("আমি তোমাকে ভালোবাসি", "bn");
    understood({ intent: "lyrics", language: "bn", romanized: "ami tomake bhalobashi", guesses: [], query: null });
    const j = (await (await voice(anon, wav(4), "?langs=bn")).json()) as any;
    expect(whisper.mock.calls[1][1].language).toBe("bn");
    expect(j.results[0]).toMatchObject({ id: s.id });
  });

  it("no bn/hi hint and a detected bn: the detected language is forced", async () => {
    heard("আমি তোমা", "bn");
    understood({ intent: "lyrics", language: "bn", romanized: "ami toma", guesses: [], query: null });
    inworldSays("");
    await voice(anon, wav(3));
    expect(inworldCalls()[0].language).toBe("bn");
  });

  it("pass 1 with matches: no second pass", async () => {
    const s = await bengaliSong();
    heard("ami tomake bhalobashi", "bn");
    understood({ intent: "lyrics", language: "bn", romanized: "ami tomake bhalobashi", guesses: [], query: null });
    const j = (await (await voice(anon, wav(3), "?langs=bn")).json()) as any;
    expect(j.results[0]).toMatchObject({ id: s.id });
    expect(whisper).toHaveBeenCalledTimes(1);
    expect(inworldFetch).not.toHaveBeenCalled();
    expect(llm).toHaveBeenCalledTimes(1);
  });

  it("no bn/hi hint or detection: no second pass", async () => {
    heard("some english words", "en");
    understood({ intent: "lyrics", language: "en", romanized: "some english words", guesses: [], query: null });
    const j = (await (await voice(anon, wav(3), "?langs=en,ta")).json()) as any;
    expect(j.results).toEqual([]);
    expect(whisper).toHaveBeenCalledTimes(1);
    expect(inworldFetch).not.toHaveBeenCalled();
  });

  it("Workers AI already detected the language and Inworld has no key: forcing it again is skipped", async () => {
    inworldKey = undefined;
    heard("কিছু একটা", "bn");
    understood({ intent: "lyrics", language: "bn", romanized: "kichu ekta", guesses: [], query: null });
    await voice(anon, wav(3), "?langs=bn");
    expect(whisper).toHaveBeenCalledTimes(1);
  });

  it("both passes find nothing: pass 1's answer is returned", async () => {
    heard("tomato", "en");
    understood({ intent: "lyrics", language: "en", romanized: "tomato", guesses: [], query: "tomato" });
    inworldSays("টমেটো");
    understood({ intent: "lyrics", language: "bn", romanized: "tometo", guesses: [], query: "tometo" });
    const j = (await (await voice(anon, wav(3), "?langs=bn")).json()) as any;
    expect(j).toMatchObject({ transcript: "tomato", romanized: "tomato", webQuery: "tomato", results: [] });
  });

  it("pass 2 failing entirely keeps pass 1's 200 and the single unit", async () => {
    heard("tomato", "en");
    understood({ intent: "lyrics", language: "en", romanized: "tomato", guesses: [], query: "tomato" });
    inworldFetch.mockRejectedValueOnce(new TypeError("network"));
    whisper.mockRejectedValueOnce(new Error("down"));
    const r = await voice(anon, wav(3), "?langs=hi");
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).transcript).toBe("tomato");
    expect(await counter("voice_user", anon.id)).toBe(1);
  });

  it("pass 1 heard no words (silence): no second pass", async () => {
    heard("", "bn");
    const j = (await (await voice(anon, wav(3), "?langs=bn")).json()) as any;
    expect(j).toMatchObject({ intent: "none", romanized: "", results: [] });
    expect(whisper).toHaveBeenCalledTimes(1);
    expect(inworldFetch).not.toHaveBeenCalled();
    expect(llm).not.toHaveBeenCalled();
  });

  it("pass 2 hearing the same words as pass 1 makes no second LLM call", async () => {
    heard("tomato", "en");
    understood({ intent: "lyrics", language: "en", romanized: "tomato", guesses: [], query: "tomato" });
    inworldSays("tomato");
    await voice(anon, wav(3), "?langs=bn");
    expect(llm).toHaveBeenCalledTimes(1);
  });
});
