import { Hono } from "hono";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import { randomToken, sha256Hex } from "../lib/crypto";
import { readJsonBody } from "../lib/body";
import { clientIpKey } from "../lib/ip";
import { isAdmin, type AppEnv, type AuthUser } from "./devices";
import { aiUsedToday, effectivePlan, intSetting, limitForRow, utcDay } from "./plans";

const MAX_ANON_BODY = 4 * 1024;
/** Anonymous accounts per client IP key per hour. Devices behind one NAT share it (accepted trade-off). */
export const ANON_PER_HOUR = 5;
const ANON_WINDOW_MS = 60 * 60_000;

export const accountsApp = new Hono<AppEnv>();

/**
 * Reserve one account-creation slot for a client IP key: ANON_PER_HOUR per key per hour, or 429
 * rate_limited. The attempt row is inserted and counted in one D1 batch (a transaction), so a
 * parallel burst cannot pass the cap; a rejected request releases its own row. Also used by
 * tokenless Google sign-in, under its own key prefix ("g:").
 */
export async function reserveAccountSlot(db: D1Database, key: string, now = Date.now()): Promise<void> {
  const ipKey = key.slice(0, 64);
  const since = now - ANON_WINDOW_MS;
  const res = await db.batch<{ n: number }>([
    db.prepare("DELETE FROM account_attempts WHERE at <= ?").bind(since),
    db.prepare("INSERT INTO account_attempts (ip_key, at) VALUES (?, ?)").bind(ipKey, now),
    db.prepare("SELECT COUNT(*) AS n FROM account_attempts WHERE ip_key = ? AND at > ?").bind(ipKey, since),
  ]);
  if ((res[2].results[0]?.n ?? 0) > ANON_PER_HOUR) {
    await db.prepare("DELETE FROM account_attempts WHERE rowid = ?").bind(res[1].meta.last_row_id).run();
    throw new ApiError(429, "rate_limited");
  }
}

/**
 * Create an anonymous `user` account plus its first device, returning the bearer token.
 * Unauthenticated (exempted in index.ts). The slot is reserved before anything is created: the
 * attempt row is inserted and counted in one D1 batch (a transaction), so a parallel burst from
 * one IP (or IPv6 /64) cannot pass the cap. A rejected request releases its own row, so only
 * successful creations count toward the hourly limit.
 */
accountsApp.post("/v1/accounts/anonymous", async (c) => {
  const body = await readJsonBody(c, MAX_ANON_BODY);
  const deviceName =
    typeof body.deviceName === "string" && body.deviceName.trim()
      ? body.deviceName.trim().slice(0, 100)
      : "Android device";

  const db = c.env.DB;
  const now = Date.now();
  await reserveAccountSlot(db, clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown"), now);

  const id = ulid();
  const deviceToken = randomToken(32);
  await db.batch([
    db.prepare(
      "INSERT INTO users (id, name, role, password_hash, created_at, plan, last_seen_at) VALUES (?, ?, 'user', NULL, ?, 'free', ?)",
    ).bind(id, `anon-${id.slice(-8).toLowerCase()}`, now, now),
    db.prepare(
      "INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES (?, ?, ?, ?, NULL, ?, ?)",
    ).bind(ulid(), id, deviceName, await sha256Hex(deviceToken), now, now),
  ]);
  c.header("Cache-Control", "no-store");
  return c.json({ deviceToken, user: { id, role: "user", plan: "free" } }, 201);
});

type MeRow = {
  id: string;
  role: string | null;
  plan: "free" | "plus";
  plan_expires_at: number | null;
  google_sub: string | null;
  email: string | null;
};

export { effectivePlan };

/**
 * Daily AI allowance for /v1/me. Owner/admin: the global ai_daily_limit and what is left of it.
 * User: the plan's daily limit (planLimit tiers: anon/free/plus), minus today's user_ai_usage,
 * capped by the global remainder.
 */
export async function aiAllowance(db: D1Database, user: AuthUser, row: MeRow) {
  const day = utcDay();
  const [globalLimit, globalUsed] = await Promise.all([
    intSetting(db, "ai_daily_limit", 100),
    db.prepare("SELECT count FROM ai_usage WHERE day = ?").bind(day).first<{ count: number }>(),
  ]);
  const globalLeft = Math.max(0, globalLimit - (globalUsed?.count ?? 0));
  const [{ limit }, used] = await Promise.all([limitForRow(db, user.role, row), aiUsedToday(db, user.id, day)]);
  if (limit === null) return { aiDailyLimit: globalLimit, aiLeftToday: globalLeft };
  return { aiDailyLimit: limit, aiLeftToday: Math.min(globalLeft, Math.max(0, limit - used)) };
}

accountsApp.get("/v1/me", async (c) => {
  const user = c.var.user;
  const row = await c.env.DB.prepare(
    "SELECT id, role, plan, plan_expires_at, google_sub, email FROM users WHERE id = ?",
  )
    .bind(user.id)
    .first<MeRow>();
  if (!row) throw new ApiError(401, "unauthorized");
  const ai = await aiAllowance(c.env.DB, user, row);
  c.header("Cache-Control", "no-store");
  const plan = effectivePlan(row.plan, row.plan_expires_at);
  return c.json({
    id: row.id,
    role: user.role,
    plan,
    planExpiresAt: plan === "plus" ? row.plan_expires_at : null,
    google: row.google_sub ? { email: row.email } : null,
    aiLeftToday: ai.aiLeftToday,
    aiDailyLimit: ai.aiDailyLimit,
  });
});
