import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { getSecret } from "../lib/secrets";
import { extractPageText } from "./html";

/**
 * Fetch a user-supplied chord-sheet page. SSRF is the threat model here:
 * - assertPublicUrl gates the first URL and every redirect hop (redirect: "manual", max 3 hops);
 * - bodies are read with a 2 MB cap under a 15 s deadline; only HTML/plain-text types are accepted;
 * - when the site blocks us (403/429/503, captcha, or too little text) the page is fetched by Apify
 *   instead. The Apify token never appears in an error, a log line or a returned value.
 * Apify is always given the ORIGINAL user URL (not a redirect target): it fetches from Apify's own
 * network, so SSRF against us does not apply there, and the URL was already checked.
 * Worst-case latency is about 90 s: the 15 s direct deadline plus the 75 s Apify deadline.
 */

export const MAX_BYTES = 2 * 1024 * 1024;
export const TIMEOUT_MS = 15_000;
export const MAX_REDIRECTS = 3;
const APIFY_TIMEOUT_MS = 75_000; // actor run is capped at 60 s server side (?timeout=60)
const APIFY_MAX_BYTES = 4 * 1024 * 1024;
export const DEFAULT_APIFY_ACTOR = "apify~rag-web-browser";
const MIN_TEXT = 200;
/** Cloudflare challenge markers in the raw HTML: always go to Apify. */
/**
 * Challenge detection (a challenge page goes to Apify instead):
 * - a Cloudflare interstitial <title> ("Just a moment..." / "Attention Required!") always counts;
 * - challenge markers in the raw HTML count only when the page has no <pre> block and less than
 *   WEAK_TEXT_LIMIT chars of text. Normal pages carry "/cdn-cgi/challenge-platform/scripts/jsd/..."
 *   (Cloudflare JS Detections), so only the interstitial path "/h/" and the challenge globals are used.
 */
const CHALLENGE_MARKERS = ["/cdn-cgi/challenge-platform/h/", "cf_chl_opt", "__cf_chl"];
const CHALLENGE_TITLE = /^\s*(just a moment\s*(\.\.\.|\u2026)?\s*$|attention required!)/i;
/** Weak markers: only checked in the extracted text, and only when that text is short. */
const WEAK_CHALLENGE = ["captcha", "verify you are human"];
const WEAK_TEXT_LIMIT = 1500;
const ALLOWED_TYPES = new Set(["text/html", "text/plain", "application/xhtml+xml"]);
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export type FetchPageResult = { text: string; via: "direct" | "apify"; finalUrl: string };
export type FetchPageDeps = {
  /** Resolves the Apify token; defaults to getSecret(env, "APIFY_TOKEN") (key store, then env). */
  getToken: () => Promise<string | undefined>;
};

const badUrl = () => new ApiError(400, "bad_url");
const fetchFailed = () => new ApiError(502, "fetch_failed");

// ---------- URL guard ----------

function ipv4Blocked(o: number[]): boolean {
  const [a, b, c] = o;
  return (
    a === 0 || // "this" network
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // CGNAT
    (a === 169 && b === 254) || // link-local / cloud metadata
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && (c === 0 || c === 2)) || // IETF protocol assignments, TEST-NET-1
    (a === 192 && b === 88 && c === 99) || // 6to4 relay anycast
    (a === 198 && (b === 18 || b === 19)) || // benchmarking
    (a === 198 && b === 51 && c === 100) || // TEST-NET-2
    (a === 203 && b === 0 && c === 113) || // TEST-NET-3
    a >= 224 // multicast, reserved, broadcast
  );
}

/** Parse a canonical dotted-quad; null when it is not one. */
function parseDottedQuad(h: string): number[] | null {
  const m = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return null;
  const o = m.slice(1).map(Number);
  return o.every((n) => n <= 255) ? o : null;
}

/** Expand an IPv6 literal (no brackets, no zone) into 8 16-bit groups; null when malformed. */
function parseIpv6(addr: string): number[] | null {
  let s = addr.toLowerCase();
  // Trailing embedded IPv4 (e.g. ::ffff:1.2.3.4) -> two hex groups.
  const v4 = s.match(/^(.*:)(\d{1,3}(?:\.\d{1,3}){3})$/);
  if (v4) {
    const o = parseDottedQuad(v4[2]);
    if (!o) return null;
    s = `${v4[1]}${((o[0] << 8) | o[1]).toString(16)}:${((o[2] << 8) | o[3]).toString(16)}`;
  }
  const halves = s.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  let groups: string[];
  if (halves.length === 2) {
    const missing = 8 - head.length - tail.length;
    if (missing < 1) return null;
    groups = [...head, ...Array<string>(missing).fill("0"), ...tail];
  } else {
    groups = head;
  }
  if (groups.length !== 8 || !groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return null;
  return groups.map((g) => parseInt(g, 16));
}

function ipv6Blocked(g: number[]): boolean {
  const embedded = (hi: number, lo: number) => [hi >> 8, hi & 0xff, lo >> 8, lo & 0xff];
  if (g[0] === 0) return true; // ::, ::1, IPv4-compatible ::a.b.c.d and IPv4-mapped ::ffff:a.b.c.d
  if ((g[0] & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
  if ((g[0] & 0xffc0) === 0xfe80) return true; // fe80::/10 link-local
  if ((g[0] & 0xffc0) === 0xfec0) return true; // fec0::/10 site-local (deprecated)
  if ((g[0] & 0xff00) === 0xff00) return true; // multicast
  if (g[0] === 0x64 && g[1] === 0xff9b) return true; // NAT64 64:ff9b::/96 and 64:ff9b:1::/48
  if (g[0] === 0x2001 && g[1] === 0x0db8) return true; // documentation
  if (g[0] === 0x0100 && g[1] === 0 && g[2] === 0 && g[3] === 0) return true; // 100::/64 discard-only
  if (g[0] === 0x2001 && g[1] === 0) return true; // Teredo (embeds arbitrary IPv4)
  if (g[0] === 0x2001 && ((g[1] & 0xfff0) === 0x10 || (g[1] & 0xfff0) === 0x20)) return true; // ORCHID, ORCHIDv2
  if (g[0] === 0x2002) return ipv4Blocked(embedded(g[1], g[2])); // 6to4
  return false;
}

/**
 * Validate a user-supplied URL for server-side fetching. Throws ApiError(400, "bad_url") unless it is
 * http(s), has no credentials, uses the default port (or 80/443), and names a public host.
 */
export function assertPublicUrl(raw: string): URL {
  if (typeof raw !== "string" || raw.length > 2048) throw badUrl();
  let u: URL;
  try {
    u = new URL(raw.trim());
  } catch {
    throw badUrl();
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") throw badUrl();
  if (u.username || u.password) throw badUrl();
  if (u.port !== "" && u.port !== "80" && u.port !== "443") throw badUrl();

  let host = u.hostname.toLowerCase();
  if (!host) throw badUrl();

  if (host.startsWith("[")) {
    if (!host.endsWith("]")) throw badUrl();
    const groups = parseIpv6(host.slice(1, -1).split("%")[0]);
    if (!groups || ipv6Blocked(groups)) throw badUrl();
    return u;
  }

  host = host.replace(/\.+$/, "");
  if (!host) throw badUrl();
  // The URL parser canonicalises decimal/octal/hex IPv4 forms (2130706433, 0x7f.1) to dotted quads.
  // Anything numeric-looking that is still not a canonical quad is refused rather than guessed at.
  const quad = parseDottedQuad(host);
  if (quad) {
    if (ipv4Blocked(quad)) throw badUrl();
    return u;
  }
  const lastLabel = host.split(".").pop() ?? "";
  if (/^(0x[0-9a-f]*|\d+)$/i.test(lastLabel)) throw badUrl();

  if (!host.includes(".")) throw badUrl(); // localhost, intranet single-label names
  if (
    host.endsWith(".localhost") ||
    host.endsWith(".local") ||
    host.endsWith(".internal") ||
    host.endsWith(".home.arpa") ||
    host.endsWith(".lan")
  ) {
    throw badUrl();
  }
  return u;
}

// ---------- bounded body read ----------

/** Read up to maxBytes of a response body, cancelling the rest. Returns the (possibly cut) bytes. */
export async function readCapped(res: Response, maxBytes: number): Promise<Uint8Array> {
  const stream = res.body;
  if (!stream) return new Uint8Array(0);
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    const room = maxBytes - total;
    if (value.byteLength >= room) {
      if (room > 0) chunks.push(value.subarray(0, room));
      total = maxBytes;
      await reader.cancel().catch(() => {});
      break;
    }
    chunks.push(value);
    total += value.byteLength;
  }
  const buf = new Uint8Array(total);
  let off = 0;
  for (const ch of chunks) {
    buf.set(ch, off);
    off += ch.byteLength;
  }
  return buf;
}

function decodeBody(bytes: Uint8Array, contentType: string): string {
  // A UTF-8 BOM wins over any declared charset (HTML encoding sniffing order).
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) return new TextDecoder("utf-8").decode(bytes);
  let cs = contentType.match(/charset\s*=\s*"?([\w.:-]+)/i)?.[1];
  if (!cs) {
    // No charset header: sniff <meta charset> / http-equiv in the first 1 KB.
    const head = new TextDecoder("latin1").decode(bytes.subarray(0, 1024));
    cs = head.match(/<meta\b[^>]*?charset\s*=\s*["']?([\w.:-]+)/i)?.[1];
    // A meta tag readable as ASCII cannot really be UTF-16; browsers treat it as UTF-8.
    if (cs && /^utf-?16/i.test(cs)) cs = "utf-8";
  }
  try {
    return new TextDecoder(cs ?? "utf-8").decode(bytes);
  } catch {
    return new TextDecoder("utf-8").decode(bytes);
  }
}

// ---------- direct fetch ----------

type DirectOutcome =
  | { kind: "ok"; text: string; finalUrl: string }
  /** `usable`: direct text worth returning if Apify is unavailable or fails. */
  | { kind: "fallback"; usable?: { text: string; finalUrl: string } };

async function fetchDirect(start: URL): Promise<DirectOutcome> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    let url = start;
    let res: Response;
    for (let hop = 0; ; hop++) {
      try {
        res = await fetch(url.href, {
          method: "GET",
          redirect: "manual",
          signal: ctrl.signal,
          headers: {
            "user-agent": USER_AGENT,
            accept: "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8",
            "accept-language": "en-US,en;q=0.8",
          },
        });
      } catch {
        return { kind: "fallback" }; // network error or timeout: let Apify try
      }
      if (res.status >= 300 && res.status < 400 && res.status !== 304) {
        await res.body?.cancel().catch(() => {});
        const loc = res.headers.get("location");
        if (!loc) throw fetchFailed();
        if (hop >= MAX_REDIRECTS) throw fetchFailed();
        let next: string;
        try {
          next = new URL(loc, url).href;
        } catch {
          throw badUrl();
        }
        url = assertPublicUrl(next);
        continue;
      }
      break;
    }

    if (res.status === 403 || res.status === 429 || res.status === 503) {
      await res.body?.cancel().catch(() => {});
      return { kind: "fallback" };
    }
    if (!res.ok) {
      await res.body?.cancel().catch(() => {});
      throw fetchFailed();
    }

    const contentType = res.headers.get("content-type") ?? "";
    const mime = contentType.split(";")[0].trim().toLowerCase();
    if (!ALLOWED_TYPES.has(mime)) {
      await res.body?.cancel().catch(() => {});
      throw new ApiError(415, "unsupported_type");
    }

    let raw: string;
    try {
      raw = decodeBody(await readCapped(res, MAX_BYTES), contentType);
    } catch {
      return { kind: "fallback" }; // body timed out or the stream broke
    }

    let text: string;
    let preBlocks = 0;
    if (mime === "text/plain") {
      text = raw.replace(/\r\n?/g, "\n").trim();
    } else {
      const lower = raw.toLowerCase();
      const page = extractPageText(raw);
      if (CHALLENGE_TITLE.test(page.title)) return { kind: "fallback" };
      text = page.text;
      preBlocks = page.preBlocks;
      if (preBlocks === 0 && text.length < WEAK_TEXT_LIMIT && CHALLENGE_MARKERS.some((m) => lower.includes(m))) {
        return { kind: "fallback" };
      }
    }
    if (preBlocks === 0 && text.length < WEAK_TEXT_LIMIT) {
      const lt = text.toLowerCase();
      if (WEAK_CHALLENGE.some((m) => lt.includes(m))) {
        return { kind: "fallback", usable: text.length >= MIN_TEXT ? { text, finalUrl: url.href } : undefined };
      }
    }
    if (text.length < MIN_TEXT) return { kind: "fallback" };
    return { kind: "ok", text, finalUrl: url.href };
  } finally {
    clearTimeout(timer);
  }
}

// ---------- Apify ----------

async function apifyActor(env: Env): Promise<string> {
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_actor'").first<{ value: string | null }>();
    const v = row?.value?.trim();
    if (v && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}~[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(v)) return v;
  } catch {
    /* settings unavailable: use the default */
  }
  return DEFAULT_APIFY_ACTOR;
}

async function fetchViaApify(env: Env, url: string, deps: FetchPageDeps): Promise<string> {
  let token: string | undefined;
  try {
    token = await deps.getToken();
  } catch {
    token = undefined;
  }
  if (!token) throw fetchFailed();
  const actor = await apifyActor(env);
  try {
    const res = await fetch(`https://api.apify.com/v2/acts/${actor}/run-sync-get-dataset-items?timeout=60`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ query: url, maxResults: 1, outputFormats: ["markdown"] }),
      signal: AbortSignal.timeout(APIFY_TIMEOUT_MS),
    });
    if (!res.ok) {
      await res.body?.cancel().catch(() => {});
      throw fetchFailed();
    }
    const items: unknown = JSON.parse(new TextDecoder().decode(await readCapped(res, APIFY_MAX_BYTES)));
    const first = Array.isArray(items) ? (items[0] as Record<string, unknown> | undefined) : undefined;
    const text =
      (typeof first?.markdown === "string" && first.markdown.trim()) ||
      (typeof first?.text === "string" && first.text.trim()) ||
      "";
    if (!text) throw fetchFailed();
    return text.length > MAX_BYTES ? text.slice(0, MAX_BYTES) : text;
  } catch {
    // Swallow the original error: it may echo request headers (the token).
    throw fetchFailed();
  }
}

// ---------- entry point ----------

export async function fetchPage(
  env: Env,
  url: string,
  deps: FetchPageDeps = { getToken: () => getSecret(env, "APIFY_TOKEN") },
): Promise<FetchPageResult> {
  const start = assertPublicUrl(url);
  const direct = await fetchDirect(start);
  if (direct.kind === "ok") return { text: direct.text, via: "direct", finalUrl: direct.finalUrl };
  let text: string;
  try {
    text = await fetchViaApify(env, start.href, deps);
  } catch (e) {
    // Apify unavailable (no token) or failed: a usable direct page beats a 502.
    if (direct.usable) return { text: direct.usable.text, via: "direct", finalUrl: direct.usable.finalUrl };
    throw e;
  }
  return { text, via: "apify", finalUrl: start.href };
}
