import type { Env } from "../env";
import { ApiError } from "./errors";

export const KEY_NAMES = [
  "INWORLD_API_KEY",
  "OPENAI_API_KEY",
  "ANTHROPIC_API_KEY",
  "APIFY_TOKEN",
  "CUSTOM_AI_KEY_1",
  "CUSTOM_AI_KEY_2",
] as const;
export type KeyName = (typeof KEY_NAMES)[number];

const enc = new TextEncoder();
const dec = new TextDecoder();

function isKeyName(n: string): n is KeyName {
  return (KEY_NAMES as readonly string[]).includes(n);
}

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

const validHex = (hex: string | undefined): hex is string => !!hex && /^[0-9a-fA-F]{64}$/.test(hex);

/** Imported keys, per hex value (an isolate rarely sees more than one). Failed imports are dropped. */
const keyCache = new Map<string, Promise<CryptoKey>>();

async function importKey(hex: string | undefined): Promise<CryptoKey | null> {
  if (!validHex(hex)) return null;
  let p = keyCache.get(hex);
  if (!p) {
    const raw = new Uint8Array(32);
    for (let i = 0; i < 32; i++) raw[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    p = crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
    keyCache.set(hex, p);
    p.catch(() => keyCache.delete(hex));
  }
  return p;
}

/** True when SECRETS_KEY is present and well-formed (portal-stored keys can be read and written). */
export function secretsEnabled(env: Env): boolean {
  return validHex(env.SECRETS_KEY);
}

function envValue(env: Env, name: string): string | undefined {
  const v = (env as unknown as Record<string, unknown>)[name];
  return typeof v === "string" && v !== "" ? v : undefined;
}

export async function getSecret(env: Env, name: string): Promise<string | undefined> {
  if (!isKeyName(name)) return undefined;
  try {
    const key = await importKey(env.SECRETS_KEY);
    if (key) {
      const row = await env.DB.prepare("SELECT ciphertext, iv FROM secrets WHERE name = ?")
        .bind(name)
        .first<{ ciphertext: string; iv: string }>();
      if (row) {
        const plain = await crypto.subtle.decrypt(
          { name: "AES-GCM", iv: unb64(row.iv), additionalData: enc.encode(name) },
          key,
          unb64(row.ciphertext),
        );
        return dec.decode(plain);
      }
    }
  } catch {
    // Undecryptable / unreadable row: fall back to env. Never log the cause.
  }
  return envValue(env, name);
}

export async function setSecret(env: Env, name: string, value: string): Promise<void> {
  if (!isKeyName(name)) throw new ApiError(400, "invalid_name");
  if (typeof value === "string") value = value.trim();
  if (typeof value !== "string" || value.length < 8 || value.length > 4096) {
    throw new ApiError(400, "invalid_value");
  }
  const key = await importKey(env.SECRETS_KEY);
  if (!key) throw new ApiError(503, "secrets_disabled");
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: enc.encode(name) },
      key,
      enc.encode(value),
    ),
  );
  await env.DB.prepare(
    `INSERT INTO secrets (name, ciphertext, iv, last4, updated_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(name) DO UPDATE SET ciphertext = excluded.ciphertext, iv = excluded.iv,
       last4 = excluded.last4, updated_at = excluded.updated_at`,
  )
    .bind(name, b64(ct), b64(iv), value.slice(-4), Date.now())
    .run();
}

export async function deleteSecret(env: Env, name: string): Promise<void> {
  if (!isKeyName(name)) throw new ApiError(400, "invalid_name");
  await env.DB.prepare("DELETE FROM secrets WHERE name = ?").bind(name).run();
}

export type SecretStatus = {
  name: KeyName;
  source: "portal" | "env" | "missing";
  last4?: string;
  /** A portal row exists but SECRETS_KEY is missing or invalid, so it cannot be decrypted (env is used instead). */
  unreadable?: true;
};

/** Env values shorter than this get no last4: four characters would give away too much of them. */
const MIN_ENV_LAST4_LEN = 8;

/**
 * Key status for the portal, without decrypting anything. Note: a well-formed but *wrong*
 * SECRETS_KEY (e.g. rotated without re-saving keys) still shows portal rows as "portal", while
 * getSecret fails to decrypt them and silently falls back to the env value. Only a missing or
 * malformed SECRETS_KEY is flagged `unreadable`.
 */
export async function secretStatus(env: Env): Promise<SecretStatus[]> {
  const rows = await env.DB.prepare("SELECT name, last4 FROM secrets").all<{ name: string; last4: string }>();
  const stored = new Map(rows.results.map((r) => [r.name, r.last4]));
  const readable = secretsEnabled(env);
  return KEY_NAMES.map((name): SecretStatus => {
    const l4 = stored.get(name);
    if (l4 !== undefined) {
      return readable ? { name, source: "portal", last4: l4 } : { name, source: "portal", last4: l4, unreadable: true };
    }
    const ev = envValue(env, name);
    if (ev) return ev.length >= MIN_ENV_LAST4_LEN ? { name, source: "env", last4: ev.slice(-4) } : { name, source: "env" };
    return { name, source: "missing" };
  });
}
