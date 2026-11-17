import { Hono } from "hono";
import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import { randomToken, sha256Hex } from "../lib/crypto";
import { readJsonBody } from "../lib/body";
import { clientIpKey } from "../lib/ip";
import { effectivePlan, reserveAccountSlot } from "./accounts";
import { asRole, type AppEnv, type AuthUser } from "./devices";

/**
 * Google sign-in: ID-token verification (RS256 against Google's JWKS) and
 * POST /v1/accounts/google, which links, merges or signs in. Tokens are never logged.
 */

const CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
/** Cache API key for the JWKS. A synthetic host so it can never collide with a real subrequest. */
const CACHE_KEY = "https://google-jwks.orbittuner.internal/oauth2/v3/certs";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
const SKEW_MS = 60_000;
const MAX_TTL_S = 6 * 3600;
const MIN_TTL_S = 60;
const DEFAULT_TTL_S = 3600;
/** At most one forced (unknown-kid) JWKS refetch per this interval, so random kids cannot hammer Google. */
const FORCED_REFETCH_COOLDOWN_MS = 60_000;
/** When Google cannot be reached, keep verifying with the expired key set for this long past its expiry. */
const STALE_GRACE_MS = 24 * 3600_000;
const MAX_ID_TOKEN = 4096;
const MAX_BODY = 8 * 1024;

const invalid = () => new ApiError(401, "invalid_google_token");
const enc = new TextEncoder();

export type GoogleIdentity = { sub: string; email: string; emailVerified: true; name?: string };

// ---- JWKS cache: module memory (per isolate) backed by the Cache API (per colo) ----

type KeySet = { keys: Map<string, CryptoKey>; expiresAt: number };
let keySet: KeySet | null = null;
/** The load in flight. A forced load never joins a normal one (that may be a Cache API hit). */
let inflight: { p: Promise<KeySet>; forced: boolean } | null = null;
let lastForcedFetch = 0;

/** Test hook: forget every cached key. */
export async function resetGoogleKeyCache(): Promise<void> {
  keySet = null;
  inflight = null;
  lastForcedFetch = 0;
  try {
    await caches.default.delete(CACHE_KEY);
  } catch {
    /* Cache API unavailable: nothing to clear */
  }
}

function maxAge(cacheControl: string | null): number {
  const m = cacheControl?.match(/(?:^|[,\s])max-age=(\d+)/i);
  const n = m ? Number.parseInt(m[1], 10) : DEFAULT_TTL_S;
  return Math.min(MAX_TTL_S, Math.max(MIN_TTL_S, Number.isFinite(n) ? n : DEFAULT_TTL_S));
}

async function importKeys(json: unknown): Promise<Map<string, CryptoKey>> {
  const out = new Map<string, CryptoKey>();
  const list = (json as { keys?: unknown })?.keys;
  if (!Array.isArray(list)) return out;
  for (const k of list.slice(0, 20)) {
    if (typeof k !== "object" || k === null) continue;
    const { kty, kid, n, e, alg, use } = k as Record<string, unknown>;
    if (kty !== "RSA" || typeof kid !== "string" || typeof n !== "string" || typeof e !== "string") continue;
    if (alg !== undefined && alg !== "RS256") continue;
    if (use !== undefined && use !== "sig") continue;
    try {
      const key = await crypto.subtle.importKey(
        "jwk",
        { kty: "RSA", n, e, alg: "RS256", ext: true },
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"],
      );
      out.set(kid, key);
    } catch {
      /* skip a malformed key */
    }
  }
  return out;
}

async function fetchKeys(useCache: boolean): Promise<KeySet> {
  const now = Date.now();
  if (useCache) {
    try {
      const hit = await caches.default.match(CACHE_KEY);
      if (hit) {
        const expiresAt = Number(hit.headers.get("x-expires-at"));
        if (Number.isFinite(expiresAt) && expiresAt > now) {
          return { keys: await importKeys(await hit.json()), expiresAt };
        }
      }
    } catch {
      /* fall through to the network */
    }
  }
  if (!useCache) lastForcedFetch = now; // the cooldown starts only when Google is actually asked
  let res: Response;
  try {
    res = await fetch(CERTS_URL, { headers: { accept: "application/json" } });
  } catch {
    throw new ApiError(503, "google_unavailable");
  }
  if (!res.ok) throw new ApiError(503, "google_unavailable");
  const text = await res.text();
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new ApiError(503, "google_unavailable");
  }
  const ttl = maxAge(res.headers.get("cache-control"));
  const expiresAt = now + ttl * 1000;
  try {
    await caches.default.put(
      CACHE_KEY,
      new Response(text, {
        headers: { "content-type": "application/json", "cache-control": `max-age=${ttl}`, "x-expires-at": String(expiresAt) },
      }),
    );
  } catch {
    /* the module cache still holds it */
  }
  return { keys: await importKeys(json), expiresAt };
}

/**
 * The current key set. force = straight to Google (unknown kid), never joining a normal load in
 * flight. A finished load replaces the module set only if it is at least as fresh, so a slow
 * Cache API read cannot overwrite keys a forced fetch just brought in. If a normal refresh fails,
 * the expired set is still used for up to STALE_GRACE_MS past its expiry.
 */
async function loadKeys(force: boolean): Promise<KeySet> {
  if (!force && keySet && keySet.expiresAt > Date.now()) return keySet;
  let entry = inflight;
  if (!entry || (force && !entry.forced)) {
    const mine = { p: fetchKeys(!force), forced: force };
    mine.p.then(
      () => { if (inflight === mine) inflight = null; },
      () => { if (inflight === mine) inflight = null; },
    );
    inflight = entry = mine;
  }
  try {
    const ks = await entry.p;
    if (!keySet || ks.expiresAt > keySet.expiresAt) keySet = ks;
    return ks;
  } catch (e) {
    if (!force && keySet && Date.now() < keySet.expiresAt + STALE_GRACE_MS) return keySet;
    throw e;
  }
}

/** Test hooks. */
export const __googleTest = { loadKeys };

async function keyFor(kid: string): Promise<CryptoKey | null> {
  const ks = await loadKeys(false);
  const k = ks.keys.get(kid);
  if (k) return k;
  const now = Date.now();
  if (now - lastForcedFetch < FORCED_REFETCH_COOLDOWN_MS) return null;
  try {
    return (await loadKeys(true)).keys.get(kid) ?? null;
  } catch {
    return null;
  }
}

// ---- verification ----

async function googleClientIds(db: D1Database): Promise<string[]> {
  const row = await db.prepare("SELECT value FROM settings WHERE key = 'google_client_ids'").first<{ value: string | null }>();
  try {
    const v = JSON.parse(row?.value ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x.length > 0) : [];
  } catch {
    return [];
  }
}

function b64urlBytes(s: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(s)) throw invalid();
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  let bin: string;
  try {
    bin = atob(b64);
  } catch {
    throw invalid();
  }
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function b64urlJson(s: string): Record<string, unknown> {
  let v: unknown;
  try {
    v = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(b64urlBytes(s)));
  } catch {
    throw invalid();
  }
  if (typeof v !== "object" || v === null || Array.isArray(v)) throw invalid();
  return v as Record<string, unknown>;
}

/**
 * Verify a Google ID token. 503 google_not_configured when settings.google_client_ids is empty;
 * 401 invalid_google_token for any defect: not RS256, unknown kid (after one throttled refetch),
 * bad signature, iss not Google, aud not one of our client ids, expired (60 s skew), issued in the
 * future, or email not verified. The signature is checked before any claim is trusted.
 */
export async function verifyGoogleIdToken(env: Pick<Env, "DB">, idToken: string): Promise<GoogleIdentity> {
  const clientIds = await googleClientIds(env.DB);
  if (clientIds.length === 0) throw new ApiError(503, "google_not_configured");
  if (typeof idToken !== "string" || idToken.length > MAX_ID_TOKEN) throw invalid();
  const parts = idToken.split(".");
  if (parts.length !== 3 || !parts[2]) throw invalid();
  const header = b64urlJson(parts[0]);
  if (header.alg !== "RS256" || typeof header.kid !== "string" || header.kid.length > 128) throw invalid();
  const sig = b64urlBytes(parts[2]);
  const payload = b64urlJson(parts[1]);

  const key = await keyFor(header.kid);
  if (!key) throw invalid();
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, sig, enc.encode(`${parts[0]}.${parts[1]}`));
  if (!ok) throw invalid();

  const now = Date.now();
  const { iss, aud, exp, iat, sub, email, email_verified, name } = payload;
  if (typeof iss !== "string" || !ISSUERS.has(iss)) throw invalid();
  if (typeof aud !== "string" || !clientIds.includes(aud)) throw invalid();
  if (typeof exp !== "number" || !(exp * 1000 > now - SKEW_MS)) throw invalid();
  if (typeof iat !== "number" || !(iat * 1000 <= now + SKEW_MS)) throw invalid();
  if (email_verified !== true) throw invalid();
  if (typeof sub !== "string" || sub.length < 1 || sub.length > 255) throw invalid();
  if (typeof email !== "string" || email.length < 3 || email.length > 320) throw invalid();
  return {
    sub,
    email,
    emailVerified: true,
    ...(typeof name === "string" && name ? { name: name.slice(0, 200) } : {}),
  };
}

// ---- POST /v1/accounts/google ----

type UserRow = {
  id: string;
  role: string | null;
  plan: string | null;
  plan_expires_at: number | null;
  google_sub: string | null;
  email: string | null;
  disabled_at: number | null;
};
const USER_COLS = "id, role, plan, plan_expires_at, google_sub, email, disabled_at";

const userBySub = (db: D1Database, sub: string) =>
  db.prepare(`SELECT ${USER_COLS} FROM users WHERE google_sub = ?`).bind(sub).first<UserRow>();
const userById = (db: D1Database, id: string) =>
  db.prepare(`SELECT ${USER_COLS} FROM users WHERE id = ?`).bind(id).first<UserRow>();

function publicUser(u: UserRow, email: string) {
  return { id: u.id, role: asRole(u.role), plan: effectivePlan(u.plan, u.plan_expires_at), google: { email } };
}

const isUniqueViolation = (e: unknown) => /UNIQUE constraint failed/i.test(String((e as Error)?.message ?? e));

export const googleApp = new Hono<AppEnv>();

/**
 * With a device token (user A on device D):
 *   - sub unlinked: link it to A (409 google_already_linked if A already has another sub).
 *   - sub linked to A: no-op.
 *   - sub linked to X != A: merge A into X and re-bind D to X. Returns deviceToken = D's existing
 *     token (re-binding keeps the token; its presence tells the app the identity changed and it must
 *     reset its sync cursors). Refused (409 google_linked_elsewhere) when A is owner/admin, or
 *     (409 google_already_linked) when A has its own Google identity; 403 if X is disabled.
 * Without a device token: rate-limited like anonymous sign-up; creates a device for the linked
 *   user, or a new `user` linked to the sub. Always returns a fresh deviceToken. A linked
 *   owner/admin is refused (403 admin_use_portal).
 * The verified token is the only proof of identity: nothing links or merges without it.
 */
googleApp.post("/v1/accounts/google", async (c) => {
  const body = await readJsonBody(c, MAX_BODY);
  if (typeof body.idToken !== "string" || body.idToken.length === 0) throw new ApiError(400, "invalid_input");
  const ident = await verifyGoogleIdToken(c.env, body.idToken);
  c.header("Cache-Control", "no-store");
  const db = c.env.DB;
  const current = c.get("user") as AuthUser | undefined;
  const deviceId = c.get("deviceId") as string | undefined;

  if (current && deviceId) {
    const bearer = c.req.header("authorization")!.slice("Bearer ".length);
    return c.json(await signedIn(db, current.id, deviceId, bearer, ident));
  }

  const deviceName =
    typeof body.deviceName === "string" && body.deviceName.trim()
      ? body.deviceName.trim().slice(0, 100)
      : "Android device";
  const ipKey = "g:" + clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown");
  const { status, result } = await tokenless(db, ipKey, deviceName, ident);
  return c.json(result, status);
});

async function signedIn(db: D1Database, meId: string, deviceId: string, bearer: string, ident: GoogleIdentity) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const me = await userById(db, meId);
    if (!me) throw new ApiError(401, "unauthorized");
    const x = await userBySub(db, ident.sub);

    if (!x) {
      if (me.google_sub) throw new ApiError(409, "google_already_linked");
      try {
        const r = await db
          .prepare("UPDATE users SET google_sub = ?, email = ? WHERE id = ? AND google_sub IS NULL")
          .bind(ident.sub, ident.email, me.id)
          .run();
        if (r.meta.changes) return { user: publicUser(me, ident.email) };
      } catch (e) {
        if (!isUniqueViolation(e)) throw e; // linked elsewhere concurrently: resolve again
      }
      continue;
    }

    if (x.id === me.id) {
      if (x.email !== ident.email) {
        await db.prepare("UPDATE users SET email = ? WHERE id = ?").bind(ident.email, x.id).run();
      }
      return { user: publicUser(x, ident.email) };
    }

    if (x.disabled_at != null) throw new ApiError(403, "account_disabled");
    if (me.google_sub) throw new ApiError(409, "google_already_linked");
    if (me.role !== "user") throw new ApiError(409, "google_linked_elsewhere");
    await mergeInto(db, me.id, x.id, deviceId, ident);
    return { deviceToken: bearer, user: publicUser(x, ident.email) };
  }
  throw new ApiError(409, "conflict");
}

/**
 * Merge anonymous user A into X and re-bind device D, in one D1 batch (one transaction). Every
 * statement is guarded on "D is still A's and X still holds this sub and is enabled", so a
 * concurrent merge or unlink turns the whole batch into a no-op. Set-based statements only (no
 * per-row binds), so the D1 bound-parameter limit is never approached.
 *   - favourites: A's rows upsert into X; strictly newer updated_at wins (same rule as PUT).
 *   - playlists: moved to X. Playlist ids are a global primary key and PUT refuses another user's
 *     id, so A and X can never hold the same id: no collision case exists. modified_at is stamped
 *     past both users' maxima (and now) so X's other devices pull them on their next sync.
 *   - songs.created_by, import_jobs, reports, today's-and-past AI usage counters: re-pointed to X.
 *   - A is deleted only when role 'user', no Google identity, no devices remain, and this batch
 *     really re-bound D to X (so a no-op batch never deletes anyone).
 */
export async function mergeInto(db: D1Database, a: string, x: string, deviceId: string, ident: GoogleIdentity) {
  const guard =
    "EXISTS (SELECT 1 FROM devices WHERE id = ? AND user_id = ?) AND EXISTS (SELECT 1 FROM users WHERE id = ? AND google_sub = ? AND disabled_at IS NULL)";
  const g = [deviceId, a, x, ident.sub] as const;
  const now = Date.now();
  const res = await db.batch([
    db.prepare(
      `INSERT INTO favourites (user_id, song_id, favourite, updated_at)
       SELECT ?, song_id, favourite, updated_at FROM favourites WHERE user_id = ? AND ${guard}
       ON CONFLICT(user_id, song_id) DO UPDATE SET favourite = excluded.favourite, updated_at = excluded.updated_at
       WHERE excluded.updated_at > favourites.updated_at`,
    ).bind(x, a, ...g),
    db.prepare(`DELETE FROM favourites WHERE user_id = ? AND ${guard}`).bind(a, ...g),
    db.prepare(
      `UPDATE playlists SET user_id = ?, write_token = NULL,
         modified_at = (SELECT max(?, COALESCE(MAX(modified_at), 0) + 1) FROM playlists WHERE user_id IN (?, ?))
       WHERE user_id = ? AND ${guard}`,
    ).bind(x, now, x, a, a, ...g),
    db.prepare(`UPDATE songs SET created_by = ? WHERE created_by = ? AND ${guard}`).bind(x, a, ...g),
    db.prepare(`UPDATE import_jobs SET user_id = ? WHERE user_id = ? AND ${guard}`).bind(x, a, ...g),
    db.prepare(`UPDATE reports SET user_id = ? WHERE user_id = ? AND ${guard}`).bind(x, a, ...g),
    // Flags: one per (song, user); a song both flagged keeps X's flag; never a flag on X's own song
    // (songs.created_by was re-pointed to X above, so A's own songs are excluded too).
    db.prepare(
      `INSERT INTO song_flags (song_id, user_id, reason, note, created_at, ip_key)
       SELECT song_id, ?, reason, note, created_at, ip_key FROM song_flags
        WHERE user_id = ? AND song_id NOT IN (SELECT id FROM songs WHERE created_by = ?) AND ${guard}
       ON CONFLICT DO NOTHING`,
    ).bind(x, a, x, ...g),
    db.prepare(`DELETE FROM song_flags WHERE user_id = ? AND ${guard}`).bind(a, ...g),
    db.prepare(
      `INSERT INTO user_ai_usage (user_id, day, count)
       SELECT ?, day, count FROM user_ai_usage WHERE user_id = ? AND ${guard}
       ON CONFLICT(user_id, day) DO UPDATE SET count = user_ai_usage.count + excluded.count`,
    ).bind(x, a, ...g),
    db.prepare(`DELETE FROM user_ai_usage WHERE user_id = ? AND ${guard}`).bind(a, ...g),
    db.prepare(
      `UPDATE devices SET user_id = ? WHERE id = ? AND user_id = ?
         AND EXISTS (SELECT 1 FROM users WHERE id = ? AND google_sub = ? AND disabled_at IS NULL)`,
    ).bind(x, deviceId, a, x, ident.sub),
    db.prepare("UPDATE users SET email = ? WHERE id = ? AND google_sub = ?").bind(ident.email, x, ident.sub),
    db.prepare(
      `DELETE FROM users WHERE id = ? AND role = 'user' AND google_sub IS NULL
         AND NOT EXISTS (SELECT 1 FROM devices WHERE user_id = ?)
         AND EXISTS (SELECT 1 FROM devices WHERE id = ? AND user_id = ?)`,
    ).bind(a, a, deviceId, x),
  ]);
  if (!res[10].meta.changes) throw new ApiError(409, "conflict"); // the device re-bind
}

async function tokenless(db: D1Database, ipKey: string, deviceName: string, ident: GoogleIdentity) {
  let x = await userBySub(db, ident.sub);
  if (x?.disabled_at != null) throw new ApiError(403, "account_disabled");
  // Owner/admin accounts get app devices only by linking while signed in, never from a bare Google token.
  if (x && x.role !== "user") throw new ApiError(403, "admin_use_portal");
  const now = Date.now();
  await reserveAccountSlot(db, ipKey, now);

  const deviceToken = randomToken(32);
  const tokenHash = await sha256Hex(deviceToken);
  const insertDevice = (userId: string) =>
    db.prepare(
      "INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES (?, ?, ?, ?, NULL, ?, ?)",
    ).bind(ulid(), userId, deviceName, tokenHash, now, now);

  if (!x) {
    const id = ulid();
    try {
      await db.batch([
        db.prepare(
          "INSERT INTO users (id, name, role, password_hash, created_at, plan, google_sub, email, last_seen_at) VALUES (?, ?, 'user', NULL, ?, 'free', ?, ?, ?)",
        ).bind(id, `g-${id.slice(-8).toLowerCase()}`, now, ident.sub, ident.email, now),
        insertDevice(id),
      ]);
      const created = (await userById(db, id))!;
      return { status: 201 as const, result: { deviceToken, user: publicUser(created, ident.email) } };
    } catch (e) {
      if (!isUniqueViolation(e)) throw e;
      x = await userBySub(db, ident.sub); // created concurrently: sign in to it instead
      if (!x) throw e;
      if (x.disabled_at != null) throw new ApiError(403, "account_disabled");
      if (x.role !== "user") throw new ApiError(403, "admin_use_portal");
    }
  }

  await db.batch([
    insertDevice(x.id),
    db.prepare("UPDATE users SET email = ?, last_seen_at = ? WHERE id = ?").bind(ident.email, now, x.id),
  ]);
  return { status: 200 as const, result: { deviceToken, user: publicUser(x, ident.email) } };
}
