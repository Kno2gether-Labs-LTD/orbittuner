import type { AuthUser } from "../auth/devices";
import { isAdmin } from "../auth/roles";
import { intSetting, planTier, utcDay, type PlanTier } from "../auth/plans";
import type { Env } from "../env";
import { ApiError } from "../lib/errors";

/**
 * Daily quotas in search_usage, one set per kind. Three counters per UTC day, all taken for one
 * request:
 * - user: per plan tier; owner/admin unlimited (no row);
 * - ip: per client IP key; owner/admin exempt;
 * - global: everyone, a circuit breaker -> 503 <kind>_unavailable.
 * user/ip refusals are 429 <kind>_limit_reached {limit, plan}.
 *
 * "search": uncached web searches (each one is a paid Apify run). Scopes user/ip/global,
 * settings search_daily_anon 2 / search_daily_free 10 / search_daily_plus 50, search_daily_per_ip
 * 30, search_daily_global 500. Cache hits never reserve.
 * "voice": POST /v1/voice clips (one Whisper run each). Scopes voice_user/voice_ip/voice_global,
 * settings voice_daily_anon 3 / voice_daily_free 10 / voice_daily_plus 50, voice_daily_per_ip 40,
 * voice_daily_global 1000.
 */
export type QuotaKind = "search" | "voice";

type KindConfig = {
  scopes: Record<Scope, string>;
  defaults: Record<PlanTier, number>;
  settings: Record<PlanTier, string>;
  perIp: { setting: string; fallback: number };
  global: { setting: string; fallback: number };
};

export const SEARCH_DEFAULTS: Record<PlanTier, number> = { anonymous: 2, free: 10, plus: 50 };
export const DEFAULT_SEARCH_PER_IP = 30;
export const DEFAULT_SEARCH_GLOBAL = 500;
export const VOICE_DEFAULTS: Record<PlanTier, number> = { anonymous: 3, free: 10, plus: 50 };
export const DEFAULT_VOICE_PER_IP = 40;
export const DEFAULT_VOICE_GLOBAL = 1000;

type Scope = "user" | "ip" | "global";

const KINDS: Record<QuotaKind, KindConfig> = {
  search: {
    scopes: { user: "user", ip: "ip", global: "global" },
    defaults: SEARCH_DEFAULTS,
    settings: { anonymous: "search_daily_anon", free: "search_daily_free", plus: "search_daily_plus" },
    perIp: { setting: "search_daily_per_ip", fallback: DEFAULT_SEARCH_PER_IP },
    global: { setting: "search_daily_global", fallback: DEFAULT_SEARCH_GLOBAL },
  },
  voice: {
    scopes: { user: "voice_user", ip: "voice_ip", global: "voice_global" },
    defaults: VOICE_DEFAULTS,
    settings: { anonymous: "voice_daily_anon", free: "voice_daily_free", plus: "voice_daily_plus" },
    perIp: { setting: "voice_daily_per_ip", fallback: DEFAULT_VOICE_PER_IP },
    global: { setting: "voice_daily_global", fallback: DEFAULT_VOICE_GLOBAL },
  },
};

/** Counters taken by one reservation; `scope` is the stored scope name (e.g. "user", "voice_ip"). */
export type QuotaReservation = { day: string; taken: { scope: string; key: string }[] };
export type SearchReservation = QuotaReservation;

/**
 * Reserve one unit of `kind` on every applicable counter, all or nothing. Each counter is a capped
 * conditional upsert; the whole set runs in one D1 batch. When any counter is full, the ones
 * that were taken are given back and the request is refused (global first, then user, then ip).
 */
export async function reserveQuota(
  env: Pick<Env, "DB">, kind: QuotaKind, user: AuthUser, ipKey: string,
): Promise<QuotaReservation> {
  const cfg = KINDS[kind];
  const db = env.DB;
  const day = utcDay();
  const staff = isAdmin(user.role);
  let plan: PlanTier = "free";
  const checks: { scope: Scope; key: string; limit: number }[] = [
    { scope: "global", key: "*", limit: Math.max(0, await intSetting(db, cfg.global.setting, cfg.global.fallback)) },
  ];
  if (!staff) {
    const row = await db.prepare("SELECT plan, plan_expires_at, google_sub FROM users WHERE id = ?").bind(user.id)
      .first<{ plan: string | null; plan_expires_at: number | null; google_sub: string | null }>();
    plan = planTier(row ?? { plan: "free", plan_expires_at: null, google_sub: null });
    checks.push(
      { scope: "user", key: user.id, limit: Math.max(0, await intSetting(db, cfg.settings[plan], cfg.defaults[plan])) },
      { scope: "ip", key: ipKey, limit: Math.max(0, await intSetting(db, cfg.perIp.setting, cfg.perIp.fallback)) },
    );
  }
  const res = await db.batch(checks.map((c) =>
    // INSERT ... SELECT ... WHERE: a zero limit inserts nothing; an existing row grows only below the cap.
    db.prepare(
      `INSERT INTO search_usage (scope, key, day, count) SELECT ?, ?, ?, 1 WHERE ? > 0
       ON CONFLICT(scope, key, day) DO UPDATE SET count = count + 1 WHERE count < ?`,
    ).bind(cfg.scopes[c.scope], c.key, day, c.limit, c.limit),
  ));
  const taken = checks.filter((_, i) => res[i].meta.changes > 0);
  const refused = checks.filter((_, i) => res[i].meta.changes === 0);
  const reservation: QuotaReservation = {
    day, taken: taken.map(({ scope, key }) => ({ scope: cfg.scopes[scope], key })),
  };
  if (refused.length > 0) {
    await refundQuota(env, reservation);
    const first = refused.find((c) => c.scope === "global") ?? refused.find((c) => c.scope === "user") ?? refused[0];
    if (first.scope === "global") throw new ApiError(503, `${kind}_unavailable`);
    throw new ApiError(429, `${kind}_limit_reached`, { limit: first.limit, plan });
  }
  return reservation;
}

/** Give back one unit on every counter of the reservation (the paid call failed). */
export async function refundQuota(env: Pick<Env, "DB">, r: QuotaReservation): Promise<void> {
  if (r.taken.length === 0) return;
  await env.DB.batch(r.taken.map((t) =>
    env.DB.prepare("UPDATE search_usage SET count = MAX(0, count - 1) WHERE scope = ? AND key = ? AND day = ?")
      .bind(t.scope, t.key, r.day),
  ));
}

/** Reserve one uncached web search (see reserveQuota). */
export const reserveSearch = (env: Pick<Env, "DB">, user: AuthUser, ipKey: string) =>
  reserveQuota(env, "search", user, ipKey);

/** Give back one search on every counter of the reservation (Apify failed). */
export const refundSearch = refundQuota;
