import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { isAdmin, type Role } from "./roles";

/**
 * Plan-based AI quotas. Every AI action (a text or URL conversion, one link-import item, one .txt
 * file, one discovered page) costs one unit of the user's daily allowance, counted per UTC day in
 * user_ai_usage. Tiers:
 * - anonymous: role user without a Google identity -> settings anon_ai_daily (default 1)
 * - free: Google-linked user -> free_ai_daily (3)
 * - plus: plan 'plus' with no expiry or one in the future -> plus_ai_daily (50)
 * - owner/admin: unlimited (null), still bounded by the global ai_daily_limit inside aiConvert.
 * Anonymous accounts also share a per-client-IP daily allowance (settings anon_ai_daily_per_ip,
 * default 10; ip_ai_usage), so many anonymous accounts from one network cannot drain the global
 * budget. It applies when the caller carries an `ipKey` (set by requireDevice from
 * cf-connecting-ip, IPv6 collapsed to /64).
 */
export type PlanTier = "anonymous" | "free" | "plus";
type PlanRow = { plan: string | null; plan_expires_at: number | null; google_sub: string | null };
type Who = { id: string; role: Role; ipKey?: string };

/** AI-capable requests (convert/songs with AI, imports, discover) per user per minute. */
export const AI_REQUESTS_PER_MINUTE = 10;
const RATE_WINDOW_MS = 60_000;

const DEFAULTS: Record<PlanTier, number> = { anonymous: 1, free: 3, plus: 50 };
const SETTING: Record<PlanTier, string> = { anonymous: "anon_ai_daily", free: "free_ai_daily", plus: "plus_ai_daily" };
/** Anonymous AI actions per client IP key per UTC day (settings anon_ai_daily_per_ip). */
export const DEFAULT_ANON_AI_DAILY_PER_IP = 10;

export const utcDay = (ms = Date.now()) => new Date(ms).toISOString().slice(0, 10);

export async function intSetting(db: D1Database, key: string, fallback: number): Promise<number> {
  const row = await db.prepare("SELECT value FROM settings WHERE key = ?").bind(key).first<{ value: string | null }>();
  const n = row?.value == null ? NaN : Number.parseInt(row.value, 10);
  return Number.isFinite(n) ? n : fallback;
}

/** The plan in force now: Plus with a past expiry counts as Free. */
export function effectivePlan(plan: string | null | undefined, expiresAt: number | null | undefined, now = Date.now()) {
  return plan === "plus" && (expiresAt == null || expiresAt > now) ? "plus" : "free";
}

export function planTier(row: PlanRow, now = Date.now()): PlanTier {
  if (effectivePlan(row.plan, row.plan_expires_at, now) === "plus") return "plus";
  return row.google_sub ? "free" : "anonymous";
}

/** Daily limit and tier for an already-loaded user row; limit null = unlimited (owner/admin). */
export async function limitForRow(db: D1Database, role: Role, row: PlanRow): Promise<{ limit: number | null; plan: PlanTier }> {
  const plan = planTier(row);
  if (isAdmin(role)) return { limit: null, plan };
  return { limit: Math.max(0, await intSetting(db, SETTING[plan], DEFAULTS[plan])), plan };
}

async function loadPlan(db: D1Database, user: Who): Promise<{ limit: number | null; plan: PlanTier }> {
  if (isAdmin(user.role)) return { limit: null, plan: "free" };
  const row = await db.prepare("SELECT plan, plan_expires_at, google_sub FROM users WHERE id = ?")
    .bind(user.id).first<PlanRow>();
  return limitForRow(db, user.role, row ?? { plan: "free", plan_expires_at: null, google_sub: null });
}

/** The user's AI actions per UTC day, or null (unlimited) for owner and admin. */
export async function planLimit(env: Pick<Env, "DB">, user: Who): Promise<number | null> {
  return (await loadPlan(env.DB, user)).limit;
}

export async function aiUsedToday(db: D1Database, userId: string, day = utcDay()): Promise<number> {
  const r = await db.prepare("SELECT count FROM user_ai_usage WHERE user_id = ? AND day = ?").bind(userId, day)
    .first<{ count: number }>();
  return r?.count ?? 0;
}

/**
 * Units reserved against one UTC day; null when nothing was reserved (staff or n = 0). `ipKey`
 * is set when the units were also counted against the anonymous per-IP allowance.
 */
export type AiReservation = { userId: string; day: string; ipKey?: string } | null;

/** The per-IP key and limit that apply to this caller, or null (not anonymous, or no IP known). */
async function anonIpLimit(db: D1Database, user: Who, plan: PlanTier): Promise<{ ipKey: string; limit: number } | null> {
  if (plan !== "anonymous" || !user.ipKey) return null;
  return {
    ipKey: user.ipKey.slice(0, 64),
    limit: Math.max(0, await intSetting(db, "anon_ai_daily_per_ip", DEFAULT_ANON_AI_DAILY_PER_IP)),
  };
}

/**
 * Atomically reserve `n` units of today's plan allowance, or throw 429 plan_limit_reached
 * {limit, plan} reserving nothing (all or nothing). Owner/admin reserve nothing. An anonymous
 * caller with an ipKey also reserves `n` units of the IP's anon_ai_daily_per_ip (a second
 * conditional upsert); when that is spent the user's units are given back and the same 429 is
 * thrown, so a refused request reserves nothing.
 */
export async function reserveAi(env: Pick<Env, "DB">, user: Who, n = 1): Promise<AiReservation> {
  if (n <= 0) return null;
  const { limit, plan } = await loadPlan(env.DB, user);
  if (limit === null) return null;
  const refuse = () => new ApiError(429, "plan_limit_reached", { limit, plan });
  if (n > limit) throw refuse();
  const ip = await anonIpLimit(env.DB, user, plan);
  if (ip && n > ip.limit) throw refuse();
  const day = utcDay();
  const r = await env.DB.prepare(
    `INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, ?)
     ON CONFLICT(user_id, day) DO UPDATE SET count = count + excluded.count WHERE count + excluded.count <= ?`,
  ).bind(user.id, day, n, limit).run();
  if (!r.meta.changes) throw refuse();
  if (!ip) return { userId: user.id, day };
  const ir = await env.DB.prepare(
    `INSERT INTO ip_ai_usage (ip_key, day, count) VALUES (?, ?, ?)
     ON CONFLICT(ip_key, day) DO UPDATE SET count = count + excluded.count WHERE count + excluded.count <= ?`,
  ).bind(ip.ipKey, day, n, ip.limit).run();
  if (!ir.meta.changes) {
    await refundAi(env, { userId: user.id, day }, n);
    throw refuse();
  }
  return { userId: user.id, day, ipKey: ip.ipKey };
}

/** Units left today: null = unlimited (owner/admin). Advisory only (reserve atomically after). */
export async function aiRemaining(
  env: Pick<Env, "DB">, user: Who,
): Promise<{ remaining: number | null; limit: number | null; plan: PlanTier }> {
  const { limit, plan } = await loadPlan(env.DB, user);
  if (limit === null) return { remaining: null, limit, plan };
  let remaining = Math.max(0, limit - (await aiUsedToday(env.DB, user.id)));
  const ip = await anonIpLimit(env.DB, user, plan);
  if (ip) {
    const r = await env.DB.prepare("SELECT count FROM ip_ai_usage WHERE ip_key = ? AND day = ?")
      .bind(ip.ipKey, utcDay()).first<{ count: number }>();
    remaining = Math.min(remaining, Math.max(0, ip.limit - (r?.count ?? 0)));
  }
  return { remaining, limit, plan };
}

/**
 * Atomically reserve as many of `n` units as today's allowance still has (partial reservation),
 * or throw 429 plan_limit_reached {limit, plan} when none is left. Read-before, capped upsert and
 * read-after run in one D1 batch (a transaction), so `granted` is exact under concurrency.
 * Owner/admin reserve nothing and are granted all `n`.
 */
export async function reserveAiUpTo(
  env: Pick<Env, "DB">, user: Who, n: number,
): Promise<{ reservation: AiReservation; granted: number }> {
  if (n <= 0) return { reservation: null, granted: 0 };
  const { limit, plan } = await loadPlan(env.DB, user);
  if (limit === null) return { reservation: null, granted: n };
  const db = env.DB;
  const day = utcDay();
  const read = () => db.prepare("SELECT count FROM user_ai_usage WHERE user_id = ? AND day = ?").bind(user.id, day);
  const res = await db.batch<{ count: number }>([
    read(),
    db.prepare(
      `INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, MIN(?, ?))
       ON CONFLICT(user_id, day) DO UPDATE SET count = MIN(count + ?, ?) WHERE count < ?`,
    ).bind(user.id, day, n, limit, n, limit, limit),
    read(),
  ]);
  const before = res[0].results[0]?.count ?? 0;
  const after = res[2].results[0]?.count ?? 0;
  let granted = Math.max(0, after - before);
  if (granted === 0) throw new ApiError(429, "plan_limit_reached", { limit, plan });
  const ip = await anonIpLimit(db, user, plan);
  if (!ip) return { reservation: { userId: user.id, day }, granted };
  // Anonymous: the same partial reservation against the IP's allowance, then hand back the
  // user's units the IP could not cover.
  const readIp = () => db.prepare("SELECT count FROM ip_ai_usage WHERE ip_key = ? AND day = ?").bind(ip.ipKey, day);
  const ipRes = await db.batch<{ count: number }>([
    readIp(),
    db.prepare(
      `INSERT INTO ip_ai_usage (ip_key, day, count) VALUES (?, ?, MIN(?, ?))
       ON CONFLICT(ip_key, day) DO UPDATE SET count = MIN(count + ?, ?) WHERE count < ?`,
    ).bind(ip.ipKey, day, granted, ip.limit, granted, ip.limit, ip.limit),
    readIp(),
  ]);
  const ipGranted = Math.max(0, (ipRes[2].results[0]?.count ?? 0) - (ipRes[0].results[0]?.count ?? 0));
  if (ipGranted < granted) await refundAi(env, { userId: user.id, day }, granted - ipGranted);
  granted = ipGranted;
  if (granted === 0) throw new ApiError(429, "plan_limit_reached", { limit, plan });
  return { reservation: { userId: user.id, day, ipKey: ip.ipKey }, granted };
}

/** Give back `n` units of a reservation (no-op when nothing was reserved). */
export async function refundAi(env: Pick<Env, "DB">, res: AiReservation, n = 1): Promise<void> {
  if (!res || n <= 0) return;
  const stmts = [
    env.DB.prepare("UPDATE user_ai_usage SET count = MAX(0, count - ?) WHERE user_id = ? AND day = ?")
      .bind(n, res.userId, res.day),
  ];
  if (res.ipKey) {
    stmts.push(
      env.DB.prepare("UPDATE ip_ai_usage SET count = MAX(0, count - ?) WHERE ip_key = ? AND day = ?")
        .bind(n, res.ipKey, res.day),
    );
  }
  await env.DB.batch(stmts);
}

/**
 * Count one AI-capable request against the per-user minute window, or throw 429 rate_limited.
 * Owner and admins are exempt (they are still bounded by the global ai_daily_limit).
 * Insert-then-count runs in one D1 batch (a transaction), so a parallel burst cannot pass the
 * cap; a refused request removes its own row, so only accepted requests count.
 */
export async function checkAiRate(env: Pick<Env, "DB">, user: Who, now = Date.now()): Promise<void> {
  if (isAdmin(user.role)) return;
  const db = env.DB;
  const since = now - RATE_WINDOW_MS;
  const res = await db.batch<{ n: number }>([
    db.prepare("DELETE FROM ai_request_attempts WHERE at <= ?").bind(since),
    db.prepare("INSERT INTO ai_request_attempts (user_id, at) VALUES (?, ?)").bind(user.id, now),
    db.prepare("SELECT COUNT(*) AS n FROM ai_request_attempts WHERE user_id = ? AND at > ?").bind(user.id, since),
  ]);
  if ((res[2].results[0]?.n ?? 0) > AI_REQUESTS_PER_MINUTE) {
    await db.prepare("DELETE FROM ai_request_attempts WHERE rowid = ?").bind(res[1].meta.last_row_id).run();
    throw new ApiError(429, "rate_limited");
  }
}

/** Codes that mean "no AI for this user right now"; ai:'auto' falls back to plain on these. */
export const USER_AI_LIMIT_CODES = new Set(["plan_limit_reached", "rate_limited"]);
