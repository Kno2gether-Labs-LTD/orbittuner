import { Hono } from "hono";
import type { MiddlewareHandler } from "hono";
import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import { randomToken, sha256Hex, timingSafeEqual } from "../lib/crypto";
import { readJsonBody } from "../lib/body";
import { clientIpKey } from "../lib/ip";
import { loadSession } from "./session";
import { asRole, isAdmin, type Role } from "./roles";

export { asRole, isAdmin, type Role };

/**
 * `linked`: the account has a Google identity (portal sessions count as linked; they are staff).
 * Anonymous accounts never auto-publish and their flags never count toward auto-hiding.
 */
export type AuthUser = {
  id: string;
  name: string;
  role: Role;
  linked: boolean;
  /** Client IP rate-limit key (IPv6 /64) of a device request, when the edge supplied one. */
  ipKey?: string;
};
export type AppEnv = {
  Bindings: Env;
  Variables: { user: AuthUser; deviceId: string; csrf: string; form: FormData };
};

const PAIR_TTL_MS = 10 * 60_000;
const LAST_SEEN_INTERVAL_MS = 60_000;

export async function createPairingCode(
  env: Env,
  userId: string,
): Promise<{ code: string; link: string; expiresAt: number }> {
  const code = randomToken(16);
  const expiresAt = Date.now() + PAIR_TTL_MS;
  await env.DB.prepare(
    "INSERT INTO pairing_codes (code_hash, user_id, expires_at, used_at) VALUES (?, ?, ?, NULL)",
  )
    .bind(await sha256Hex(code), userId, expiresAt)
    .run();
  const link = `orbittuner://pair?code=${code}&host=${env.PUBLIC_HOST}`;
  return { code, link, expiresAt };
}

const MAX_PAIR_BODY = 4 * 1024;

/**
 * Authenticates /v1 requests. A bearer device token always wins when an Authorization header is
 * present. Without one, the portal may call in with the owner's session cookie plus an `X-CSRF`
 * header equal to the session csrf (a custom header a cross-site page cannot send). Owner and
 * admins only (the portal roles). A disabled account gets 403 account_disabled either way.
 */
export const requireDevice: MiddlewareHandler<AppEnv> = async (c, next) => {
  const header = c.req.header("authorization");
  if (header === undefined) {
    const supplied = c.req.header("x-csrf");
    const s = supplied ? await loadSession(c) : null;
    if (!s || !isAdmin(s.role) || !timingSafeEqual(supplied!, s.csrf)) {
      throw new ApiError(401, "unauthorized");
    }
    if (s.disabled_at != null) throw new ApiError(403, "account_disabled");
    c.set("user", { id: s.user_id, name: s.name, role: s.role, linked: true });
    c.set("csrf", s.csrf);
    await next();
    return;
  }
  const m = header.match(/^Bearer ([A-Za-z0-9_-]{20,200})$/);
  if (!m) throw new ApiError(401, "unauthorized");
  const row = await c.env.DB.prepare(
    `SELECT d.id AS device_id, d.revoked_at, d.last_seen_at, u.id AS user_id, u.name, u.role, u.disabled_at,
            u.google_sub IS NOT NULL AS linked
       FROM devices d JOIN users u ON u.id = d.user_id WHERE d.token_hash = ?`,
  )
    .bind(await sha256Hex(m[1]))
    .first<{
      device_id: string;
      revoked_at: number | null;
      last_seen_at: number | null;
      user_id: string;
      name: string;
      role: string | null;
      disabled_at: number | null;
      linked: number;
    }>();
  if (!row) throw new ApiError(401, "unauthorized");
  if (row.revoked_at != null) throw new ApiError(401, "token_revoked");
  if (row.disabled_at != null) throw new ApiError(403, "account_disabled");
  const now = Date.now();
  if (row.last_seen_at == null || now - row.last_seen_at >= LAST_SEEN_INTERVAL_MS) {
    await c.env.DB.batch([
      c.env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(now, row.device_id),
      c.env.DB.prepare("UPDATE users SET last_seen_at = ? WHERE id = ?").bind(now, row.user_id),
    ]);
  }
  // Cloudflare sets cf-connecting-ip on every request that reaches the Worker (clients cannot
  // forge it through the edge). It is only absent in local dev and tests; then no per-IP AI cap
  // applies (see reserveAi) rather than charging a shared "unknown" bucket.
  const ip = c.req.header("cf-connecting-ip");
  c.set("user", {
    id: row.user_id, name: row.name, role: asRole(row.role), linked: row.linked === 1,
    ...(ip ? { ipKey: clientIpKey(ip) } : {}),
  });
  c.set("deviceId", row.device_id);
  await next();
};

export const devicesApp = new Hono<AppEnv>();

devicesApp.post("/v1/pair", async (c) => {
  let body: { code?: unknown; deviceName?: unknown };
  try {
    body = await readJsonBody(c, MAX_PAIR_BODY);
  } catch (e) {
    if (e instanceof ApiError && e.status === 413) throw e;
    throw new ApiError(400, "invalid_code");
  }
  if (typeof body?.code !== "string" || body.code.length === 0 || body.code.length > 200) {
    throw new ApiError(400, "invalid_code");
  }
  const deviceName =
    typeof body.deviceName === "string" && body.deviceName.trim()
      ? body.deviceName.trim().slice(0, 100)
      : "Android device";

  const codeHash = await sha256Hex(body.code);
  const row = await c.env.DB.prepare(
    "SELECT user_id, expires_at, used_at FROM pairing_codes WHERE code_hash = ?",
  )
    .bind(codeHash)
    .first<{ user_id: string; expires_at: number; used_at: number | null }>();
  if (!row) throw new ApiError(400, "invalid_code");
  if (row.used_at != null) throw new ApiError(410, "code_used");
  const now = Date.now();
  if (row.expires_at <= now) throw new ApiError(410, "code_expired");

  // Atomic single-use claim: only one concurrent redeemer can win.
  const claim = await c.env.DB.prepare(
    "UPDATE pairing_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
  )
    .bind(now, codeHash, now)
    .run();
  if (!claim.meta.changes) throw new ApiError(410, "code_used");

  const user = await c.env.DB.prepare("SELECT id, name, role FROM users WHERE id = ? AND disabled_at IS NULL")
    .bind(row.user_id)
    .first<AuthUser>();
  if (!user) throw new ApiError(400, "invalid_code");

  const deviceToken = randomToken(32);
  await c.env.DB.prepare(
    "INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES (?, ?, ?, ?, NULL, ?, ?)",
  )
    .bind(ulid(), user.id, deviceName, await sha256Hex(deviceToken), now, now)
    .run();
  c.header("Cache-Control", "no-store");
  return c.json({ deviceToken, user: { id: user.id, name: user.name, role: user.role } });
});

/** App sign-out: revoke the device token this request was made with. */
devicesApp.delete("/v1/devices/me", async (c) => {
  const deviceId = c.get("deviceId");
  if (!deviceId) throw new ApiError(400, "no_device"); // portal session calls have no device
  await c.env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
    .bind(Date.now(), deviceId)
    .run();
  return c.body(null, 204);
});
