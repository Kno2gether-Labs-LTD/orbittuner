import type { Role } from "../auth/roles";
import { utcDay } from "../auth/plans";

/** What the Users page can change on an account. */
export type UserAction = "plan" | "role" | "access" | "devices" | "login";
type Actor = { id: string; role: Role };

/**
 * Who may do what to whom on the Users page.
 * - Only the owner changes roles, and never their own (the owner role is not grantable).
 * - Nobody disables their own account (no self-lockout); anyone may sign their own devices out.
 * - Admins manage plain users only: never the owner, never another admin.
 * - Plans apply to plain users only (staff are bounded by the global AI limit alone).
 * - Only the owner issues portal-login links, and only for admins.
 */
export function canManage(actor: Actor, target: Actor, action: UserAction): boolean {
  if (actor.role !== "owner" && actor.role !== "admin") return false;
  if (actor.id === target.id) return action === "devices";
  if (target.role === "owner") return false;
  if (action === "plan" && target.role !== "user") return false;
  if (action === "login") return actor.role === "owner" && target.role === "admin";
  if (action === "role") return actor.role === "owner";
  return actor.role === "owner" || target.role === "user";
}

export const USER_KINDS = ["all", "google", "anonymous", "plus", "staff", "disabled"] as const;
export type UserKind = (typeof USER_KINDS)[number];
export const USERS_PAGE_SIZE = 50;

export type UserRow = {
  id: string;
  name: string;
  role: Role;
  email: string | null;
  linked: number;
  plan: "free" | "plus";
  plan_expires_at: number | null;
  created_at: number | null;
  last_seen_at: number | null;
  disabled_at: number | null;
  has_password: number;
  ai_today: number;
  songs: number;
  devices: number;
};

export type UsersQuery = { q: string; kind: UserKind; page: number };

const KIND_SQL: Record<UserKind, string> = {
  all: "",
  google: " AND u.google_sub IS NOT NULL",
  anonymous: " AND u.google_sub IS NULL AND u.role = 'user'",
  plus: " AND u.plan = 'plus' AND (u.plan_expires_at IS NULL OR u.plan_expires_at > ?)",
  staff: " AND u.role IN ('owner', 'admin')",
  disabled: " AND u.disabled_at IS NOT NULL",
};

/** One page of the Users list (newest activity first, staff on top) plus the filtered total. */
export async function listUsers(db: D1Database, query: UsersQuery, now = Date.now()) {
  const binds: unknown[] = [];
  let where = KIND_SQL[query.kind];
  if (query.kind === "plus") binds.push(now);
  if (query.q) {
    // instr(), not LIKE: no wildcards to escape and no LIKE pattern-length limit.
    const q = query.q.toLowerCase();
    where += ` AND (instr(lower(COALESCE(u.email, '')), ?) > 0 OR instr(lower(u.id), ?) > 0
                 OR (u.role IN ('owner', 'admin') AND instr(lower(u.name), ?) > 0))`;
    binds.push(q, q, q);
  }
  const [rows, count] = await db.batch([
    db.prepare(
      `SELECT u.id, u.name, u.role, u.email, u.google_sub IS NOT NULL AS linked, u.plan, u.plan_expires_at,
              u.created_at, u.last_seen_at, u.disabled_at, u.password_hash IS NOT NULL AS has_password,
              COALESCE((SELECT a.count FROM user_ai_usage a WHERE a.user_id = u.id AND a.day = ?), 0) AS ai_today,
              (SELECT COUNT(*) FROM songs s WHERE s.created_by = u.id) AS songs,
              (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id AND d.revoked_at IS NULL) AS devices
         FROM users u WHERE 1=1${where}
        ORDER BY u.role = 'owner' DESC, u.role = 'admin' DESC, COALESCE(u.last_seen_at, u.created_at) DESC, u.id DESC
        LIMIT ? OFFSET ?`,
    ).bind(utcDay(now), ...binds, USERS_PAGE_SIZE + 1, query.page * USERS_PAGE_SIZE),
    db.prepare(`SELECT COUNT(*) AS n FROM users u WHERE 1=1${where}`).bind(...binds),
  ]);
  const results = rows.results as UserRow[];
  return {
    items: results.slice(0, USERS_PAGE_SIZE),
    hasNext: results.length > USERS_PAGE_SIZE,
    total: (count.results[0] as { n: number } | undefined)?.n ?? 0,
  };
}

export type UserCounts = { total: number; google: number; anonymous: number; plus: number; today: number };

export async function userCounts(db: D1Database, now = Date.now()): Promise<UserCounts> {
  const dayStart = Date.parse(`${utcDay(now)}T00:00:00Z`);
  const r = await db.prepare(
    `SELECT COUNT(*) AS total,
            COALESCE(SUM(google_sub IS NOT NULL), 0) AS google,
            COALESCE(SUM(google_sub IS NULL AND role = 'user'), 0) AS anonymous,
            COALESCE(SUM(plan = 'plus' AND (plan_expires_at IS NULL OR plan_expires_at > ?)), 0) AS plus,
            COALESCE(SUM(last_seen_at >= ?), 0) AS today
       FROM users`,
  ).bind(now, dayStart).first<UserCounts>();
  return r ?? { total: 0, google: 0, anonymous: 0, plus: 0, today: 0 };
}

/**
 * A plan expiry from an <input type=date> value: the end of that UTC day, or null when blank.
 * Undefined when invalid, in the past, or more than ten years ahead.
 */
export function parseExpiry(raw: string, now = Date.now()): number | null | undefined {
  const s = raw.trim();
  if (!s) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (!m) return undefined;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = Date.UTC(y, mo - 1, d, 23, 59, 59, 999);
  const back = new Date(t);
  if (back.getUTCFullYear() !== y || back.getUTCMonth() !== mo - 1 || back.getUTCDate() !== d) return undefined;
  if (t <= now || t > now + 10 * 366 * 86_400_000) return undefined;
  return t;
}

/** Google OAuth client IDs, one per line: trimmed, blank lines dropped, deduplicated. */
export const CLIENT_ID_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,150}\.apps\.googleusercontent\.com$/;
export const MAX_CLIENT_IDS = 20;

export function parseClientIds(raw: string): { ids: string[]; bad: string[] } {
  const lines = raw.split(/\r\n|\r|\n/).map((l) => l.trim()).filter(Boolean);
  const bad = lines.filter((l) => !CLIENT_ID_RE.test(l));
  return { ids: [...new Set(lines.filter((l) => CLIENT_ID_RE.test(l)))], bad };
}

/**
 * Staff-added songs carrying the `private` category (any case): the owner's personal imports. A
 * member's song tagged private is never touched by the bulk actions.
 */
export const PRIVATE_SONG_SQL =
  "EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(songs.categories) THEN songs.categories ELSE '[]' END) WHERE lower(value) = 'private')" +
  " AND EXISTS (SELECT 1 FROM users su WHERE su.id = songs.created_by AND su.role IN ('owner', 'admin'))";
