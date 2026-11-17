const ITERATIONS = 100000;
const enc = new TextEncoder();

function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function unb64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Constant-time comparison of two byte arrays (length difference is folded in). */
export function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  let diff = a.length ^ b.length;
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

/** Constant-time string comparison. */
export function timingSafeEqual(a: string, b: string): boolean {
  return timingSafeEqualBytes(enc.encode(a), enc.encode(b));
}

async function derive(pw: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", enc.encode(pw), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    key,
    256,
  );
  return new Uint8Array(bits);
}

export async function hashPassword(pw: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await derive(pw, salt, ITERATIONS);
  return `pbkdf2$${ITERATIONS}$${b64(salt)}$${b64(hash)}`;
}

export async function verifyPassword(pw: string, stored: string): Promise<boolean> {
  try {
    const [scheme, iterStr, saltB64, hashB64] = stored.split("$");
    if (scheme !== "pbkdf2" || !saltB64 || !hashB64) return false;
    const iterations = Number(iterStr);
    if (!Number.isInteger(iterations) || iterations < 1 || iterations > ITERATIONS) return false;
    const expected = unb64(hashB64);
    const actual = await derive(pw, unb64(saltB64), iterations);
    return timingSafeEqualBytes(actual, expected);
  } catch {
    return false;
  }
}

export function randomToken(bytes = 32): string {
  return b64(crypto.getRandomValues(new Uint8Array(bytes)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export async function sha256Hex(s: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s)));
  return Array.from(d, (b) => b.toString(16).padStart(2, "0")).join("");
}
