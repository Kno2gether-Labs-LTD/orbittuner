import { Hono } from "hono";
import type { Context, MiddlewareHandler } from "hono";
import { deleteCookie, getCookie, setCookie } from "hono/cookie";
import { readFormBounded } from "../lib/body";
import { MAX_TOTAL_BYTES } from "../transfer/zip";
import { render } from "../portal/render";
import { AdminSetupPage, SetupPage } from "../portal/Setup";
import { LoginPage } from "../portal/Login";
import { SESSION_COOKIE, loadSession } from "./session";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import { clientIpKey } from "../lib/ip";
import {
  hashPassword,
  randomToken,
  sha256Hex,
  timingSafeEqual,
  verifyPassword,
} from "../lib/crypto";
import { isAdmin, type AppEnv } from "./devices";

const CSRF_COOKIE = "ot_csrf";
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MIN_PASSWORD = 8;

export const portalApp = new Hono<AppEnv>();

// ---------- helpers ----------

/** Byte caps for portal form posts: plain forms, the song editor, and the import upload. */
export const FORM_MAX_BYTES = 64 * 1024;
export const EDITOR_FORM_MAX_BYTES = 2 * 1024 * 1024;
export const UPLOAD_FORM_MAX_BYTES = MAX_TOTAL_BYTES + 64 * 1024;
/** 200 links of up to 2,048 characters each, URL-encoded, plus the csrf field. */
export const LINKS_FORM_MAX_BYTES = 512 * 1024;

function formCap(path: string): number {
  if (path === "/portal/transfer") return UPLOAD_FORM_MAX_BYTES;
  if (path === "/portal/transfer/links") return LINKS_FORM_MAX_BYTES;
  if (path.startsWith("/portal/songs/")) return EDITOR_FORM_MAX_BYTES;
  return FORM_MAX_BYTES;
}

/** The request's form body, read once through a size-bounded parser and cached on the context. */
export async function portalForm(c: Context<AppEnv>): Promise<FormData> {
  const cached = c.get("form");
  if (cached) return cached;
  const fd = await readFormBounded(c, formCap(c.req.path));
  c.set("form", fd);
  return fd;
}

/** A trimmed string field ("" when absent or a file). */
export function field(fd: FormData, name: string, max = 10_000): string {
  const v = fd.get(name);
  return typeof v === "string" ? v.slice(0, max) : "";
}

function setCsrfCookie(c: Context<AppEnv>): string {
  const token = randomToken(32);
  setCookie(c, CSRF_COOKIE, token, {
    httpOnly: true,
    secure: true,
    sameSite: "Lax",
    path: "/portal",
    maxAge: 3600,
  });
  return token;
}

async function startSession(c: Context<AppEnv>, userId: string) {
  const id = randomToken(32);
  const csrf = randomToken(32);
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM sessions WHERE expires_at < ?").bind(now),
    c.env.DB.prepare("INSERT INTO sessions (id_hash, user_id, csrf, expires_at) VALUES (?, ?, ?, ?)").bind(
      await sha256Hex(id),
      userId,
      csrf,
      now + SESSION_TTL_MS,
    ),
  ]);
  setCookie(c, SESSION_COOKIE, id, {
    httpOnly: true,
    secure: true,
    sameSite: "Lax",
    path: "/",
    maxAge: SESSION_TTL_MS / 1000,
  });
  deleteCookie(c, CSRF_COOKIE, { path: "/portal" });
}

/** A live portal session (owner or admin, not disabled), or null. */
async function portalSession(c: Context<AppEnv>) {
  const s = await loadSession(c);
  return s && isAdmin(s.role) && s.disabled_at == null ? s : null;
}

/** Portal pages admins may use (library, editor, review). No portal session: back to login. */
export const requireAdmin: MiddlewareHandler<AppEnv> = async (c, next) => {
  const s = await portalSession(c);
  if (!s) return c.redirect("/portal/login", 302);
  c.set("user", { id: s.user_id, name: s.name, role: s.role, linked: true });
  c.set("csrf", s.csrf);
  await next();
};

/** Owner-only portal pages (people, keys, settings, devices, import/export). An admin gets 403. */
export const requireOwner: MiddlewareHandler<AppEnv> = async (c, next) => {
  const s = await portalSession(c);
  if (!s) return c.redirect("/portal/login", 302);
  if (s.role !== "owner") throw new ApiError(403, "forbidden");
  c.set("user", { id: s.user_id, name: s.name, role: s.role, linked: true });
  c.set("csrf", s.csrf);
  await next();
};

/**
 * Origin must equal the request's own origin, and the CSRF token (`_csrf` form
 * field or `X-CSRF` header) must match the expected one. Only the pre-login
 * forms (setup, login) use the double-submit cookie; every other portal POST
 * is checked against the session's stored csrf.
 */
const PRE_LOGIN_FORMS = new Set(["/portal/setup", "/portal/login", "/portal/admin-setup"]);

const csrfGuard: MiddlewareHandler<AppEnv> = async (c, next) => {
  if (c.req.method !== "POST") return next();
  const forbidden = () => new ApiError(403, "csrf");
  const origin = c.req.header("origin");
  if (!origin || origin !== new URL(c.req.url).origin) throw forbidden();

  let expected: string | undefined;
  if (PRE_LOGIN_FORMS.has(c.req.path)) {
    expected = getCookie(c, CSRF_COOKIE);
  } else {
    const session = await loadSession(c);
    // Logging out without a (valid) session is harmless: Origin passed, so just clear the cookie.
    if (!session && c.req.path === "/portal/logout") return next();
    // Only an owner/admin session may make the portal read a body (the upload cap is 20 MB).
    expected = session && isAdmin(session.role) && session.disabled_at == null ? session.csrf : undefined;
  }
  // Nothing to compare against: refuse before reading a single body byte.
  if (!expected) throw forbidden();
  let supplied: unknown = c.req.header("x-csrf");
  if (supplied === undefined) {
    try {
      supplied = (await portalForm(c)).get("_csrf");
    } catch (e) {
      if (e instanceof ApiError && e.status === 413) throw e;
      throw forbidden();
    }
  }
  if (typeof supplied !== "string" || !timingSafeEqual(supplied, expected)) {
    throw forbidden();
  }
  await next();
};

async function ownerExists(c: Context<AppEnv>): Promise<boolean> {
  const r = await c.env.DB.prepare("SELECT 1 AS x FROM users WHERE role = 'owner' LIMIT 1").first();
  return r != null;
}

/** Setup is available only while no owner exists and the setup token matches. */
async function setupAllowed(c: Context<AppEnv>, token: unknown): Promise<boolean> {
  const secret = c.env.SETUP_TOKEN;
  const tokenOk =
    typeof secret === "string" &&
    secret.length > 0 &&
    typeof token === "string" &&
    timingSafeEqual(token, secret);
  const exists = await ownerExists(c);
  return tokenOk && !exists;
}

const setupGate: MiddlewareHandler<AppEnv> = async (c, next) => {
  let token: unknown;
  if (c.req.method === "POST") {
    try {
      token = (await portalForm(c)).get("token");
    } catch (e) {
      if (e instanceof ApiError && e.status === 413) throw e;
      token = undefined;
    }
  } else {
    token = c.req.query("token");
  }
  if (!(await setupAllowed(c, token))) throw new ApiError(404, "not_found");
  await next();
};

// The CSRF guard runs first so a body is only ever read once a csrf to compare against exists.
portalApp.use("/portal/*", csrfGuard);
portalApp.use("/portal/setup", setupGate);

// ---------- setup ----------

portalApp.get("/portal/setup", (c) => {
  const csrf = setCsrfCookie(c);
  return render(c, SetupPage({ csrf, token: c.req.query("token") ?? "", minPassword: MIN_PASSWORD }));
});

portalApp.post("/portal/setup", async (c) => {
  const body = await portalForm(c);
  const name = field(body, "name").trim().slice(0, 100);
  const password = field(body, "password");
  if (!name || password.length < MIN_PASSWORD || password.length > 1000) {
    throw new ApiError(400, "invalid_input");
  }
  const id = ulid();
  // Insert only if still no owner: closes the race between concurrent setups.
  const res = await c.env.DB.prepare(
    `INSERT INTO users (id, name, role, password_hash, created_at)
     SELECT ?, ?, 'owner', ?, ? WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'owner')`,
  )
    .bind(id, name, await hashPassword(password), Date.now())
    .run();
  if (!res.meta.changes) throw new ApiError(404, "not_found");
  await startSession(c, id);
  return c.redirect("/portal", 302);
});

// ---------- login / logout ----------

portalApp.get("/portal/login", (c) => {
  const csrf = setCsrfCookie(c);
  return render(c, LoginPage({ csrf }));
});

let dummyHash: Promise<string> | undefined;
function getDummyHash(): Promise<string> {
  if (!dummyHash) {
    const p = hashPassword("dummy-password-for-timing");
    // Do not cache a rejection forever: let the next login retry.
    p.catch(() => {
      if (dummyHash === p) dummyHash = undefined;
    });
    dummyHash = p;
  }
  return dummyHash;
}

const MAX_FAILED = 5;
const ATTEMPT_WINDOW_MS = 15 * 60_000;

portalApp.post("/portal/login", async (c) => {
  const body = await portalForm(c);
  const name = field(body, "name", 100);
  const password = field(body, "password", 1000);
  // Keys are (name+ip) and ip-only. No name-only key: it would let anyone lock the owner out.
  const ip = clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown");
  const pairKey = `pair:${name.toLowerCase()}|${ip.slice(0, 64)}`;
  const ipKey = `ip:${ip.slice(0, 64)}`;
  const now = Date.now();
  const since = now - ATTEMPT_WINDOW_MS;

  // Reserve the attempt atomically (one D1 batch/transaction) BEFORE verifying, so a
  // parallel burst cannot slip past the cap. Counts include the just-inserted rows.
  const res = await c.env.DB.batch<{ n: number }>([
    c.env.DB.prepare("DELETE FROM login_attempts WHERE at < ?").bind(since),
    c.env.DB.prepare("INSERT INTO login_attempts (key, at) VALUES (?, ?)").bind(pairKey, now),
    c.env.DB.prepare("INSERT INTO login_attempts (key, at) VALUES (?, ?)").bind(ipKey, now),
    c.env.DB.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE key = ? AND at > ?").bind(pairKey, since),
    c.env.DB.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE key = ? AND at > ?").bind(ipKey, since),
  ]);
  const ipRowId = res[2].meta.last_row_id;
  if ((res[3].results[0]?.n ?? 0) > MAX_FAILED || (res[4].results[0]?.n ?? 0) > MAX_FAILED) {
    if ((c.req.header("accept") ?? "").includes("application/json")) {
      return c.json({ error: "rate_limited" }, 429);
    }
    const csrf = setCsrfCookie(c);
    return render(c, LoginPage({ csrf, name, notice: "Too many failed attempts. Try again in 15 minutes." }), 429);
  }

  // Only the owner and admins can get a portal session; a disabled account cannot.
  // Owner first, so an admin sharing the owner's name can never shadow the owner's login.
  const user = await c.env.DB.prepare(
    `SELECT id, password_hash FROM users
      WHERE name = ? AND role IN ('owner','admin') AND password_hash IS NOT NULL AND disabled_at IS NULL
      ORDER BY role = 'owner' DESC LIMIT 1`,
  )
    .bind(name)
    .first<{ id: string; password_hash: string }>();
  // Always run one PBKDF2 verification so unknown users cost the same as wrong passwords.
  const ok = await verifyPassword(password, user?.password_hash ?? (await getDummyHash()));
  if (!user || !ok) {
    const csrf = setCsrfCookie(c);
    return render(c, LoginPage({ csrf, name, notice: "That name and password do not match." }), 401);
  }
  // Success: release this request's reservation and clear the (name+ip) counter.
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM login_attempts WHERE key = ?").bind(pairKey),
    c.env.DB.prepare("DELETE FROM login_attempts WHERE rowid = ? AND key = ?").bind(ipRowId, ipKey),
  ]);
  await startSession(c, user.id);
  return c.redirect("/portal", 302);
});

// ---------- admin portal-login links ----------

/** How long an owner-issued admin setup link stays valid. */
export const ADMIN_SETUP_TTL_MS = 24 * 60 * 60_000;

/** The admin a live, unused setup token belongs to (still an admin, not disabled), or null. */
async function adminForToken(c: Context<AppEnv>, token: string): Promise<{ id: string; name: string } | null> {
  if (!/^[A-Za-z0-9_-]{20,200}$/.test(token)) return null;
  return c.env.DB.prepare(
    `SELECT u.id, u.name FROM admin_setup_tokens t JOIN users u ON u.id = t.user_id
      WHERE t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > ? AND u.role = 'admin' AND u.disabled_at IS NULL`,
  ).bind(await sha256Hex(token), Date.now()).first<{ id: string; name: string }>();
}

/** SQL true when another owner/admin already signs in with the name bound to its two `?`s (id, name). */
const STAFF_NAME_TAKEN =
  "EXISTS (SELECT 1 FROM users o WHERE o.role IN ('owner', 'admin') AND o.id != ? AND lower(o.name) = lower(?))";

/**
 * Claim a setup token and set the admin's sign-in name and password, in one D1 batch (a
 * transaction). The claim stamps a fresh per-request nonce and only succeeds while the token is
 * live and unused, its user is still an active admin, and no other staff account has the name;
 * the user update requires that exact nonce, so a second redemption, even in the same
 * millisecond, can never set the password. A name clash leaves the token unused.
 */
export async function redeemAdminSetup(
  db: D1Database,
  r: { tokenHash: string; userId: string; name: string; passwordHash: string; now: number },
): Promise<"ok" | "name_taken" | "invalid"> {
  const nonce = randomToken(16);
  const done = await db.batch([
    db.prepare(
      `UPDATE admin_setup_tokens SET used_at = ?, claim_nonce = ?
        WHERE token_hash = ? AND user_id = ? AND used_at IS NULL AND expires_at > ?
          AND EXISTS (SELECT 1 FROM users u WHERE u.id = admin_setup_tokens.user_id AND u.role = 'admin' AND u.disabled_at IS NULL)
          AND NOT ${STAFF_NAME_TAKEN}`,
    ).bind(r.now, nonce, r.tokenHash, r.userId, r.now, r.userId, r.name),
    db.prepare(
      `UPDATE users SET name = ?, password_hash = ?
        WHERE id = ? AND role = 'admin'
          AND EXISTS (SELECT 1 FROM admin_setup_tokens t WHERE t.token_hash = ? AND t.user_id = users.id AND t.claim_nonce = ?)
          AND NOT ${STAFF_NAME_TAKEN}`,
    ).bind(r.name, r.passwordHash, r.userId, r.tokenHash, nonce, r.userId, r.name),
  ]);
  if (done[0].meta.changes && done[1].meta.changes) return "ok";
  // Nothing claimed: tell a name clash on a still-valid token apart from a dead token.
  const clash = await db.prepare(`SELECT ${STAFF_NAME_TAKEN} AS x`).bind(r.userId, r.name).first<{ x: number }>();
  const live = await db.prepare("SELECT 1 AS x FROM admin_setup_tokens WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")
    .bind(r.tokenHash, r.now).first();
  return clash?.x && live ? "name_taken" : "invalid";
}

portalApp.get("/portal/admin-setup", async (c) => {
  const token = c.req.query("token") ?? "";
  const admin = await adminForToken(c, token);
  if (!admin) throw new ApiError(404, "not_found");
  const csrf = setCsrfCookie(c);
  return render(c, AdminSetupPage({ csrf, token, name: admin.name, minPassword: MIN_PASSWORD }));
});

/**
 * Redeem an admin setup link: set the admin's sign-in name and password, burn the token and start
 * a session. Attempts are reserved per client IP before the token is checked (same window and
 * cap as login), so tokens cannot be guessed at speed; a success releases its reservation.
 */
portalApp.post("/portal/admin-setup", async (c) => {
  const body = await portalForm(c);
  const token = field(body, "token", 300);
  const name = field(body, "name", 1000).replace(/\s+/g, " ").trim();
  const password = field(body, "password", 2000);
  const ipKey = `adminsetup:${clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown").slice(0, 64)}`;
  const now = Date.now();
  const db = c.env.DB;
  const res = await db.batch<{ n: number }>([
    db.prepare("DELETE FROM login_attempts WHERE at < ?").bind(now - ATTEMPT_WINDOW_MS),
    db.prepare("INSERT INTO login_attempts (key, at) VALUES (?, ?)").bind(ipKey, now),
    db.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE key = ? AND at > ?").bind(ipKey, now - ATTEMPT_WINDOW_MS),
  ]);
  if ((res[2].results[0]?.n ?? 0) > MAX_FAILED) throw new ApiError(429, "rate_limited");
  const admin = await adminForToken(c, token);
  if (!admin) throw new ApiError(404, "not_found");
  const again = (notice: string, status: 400 | 409) =>
    render(c, AdminSetupPage({ csrf: setCsrfCookie(c), token, name: name.slice(0, 100), minPassword: MIN_PASSWORD, notice }), status);
  if (!name || name.length > 100) return again("Choose a sign-in name of up to 100 characters.", 400);
  if (password.length < MIN_PASSWORD || password.length > 1000) {
    return again(`Use a password of at least ${MIN_PASSWORD} characters.`, 400);
  }
  const outcome = await redeemAdminSetup(db, {
    tokenHash: await sha256Hex(token), userId: admin.id, name, passwordHash: await hashPassword(password), now,
  });
  if (outcome === "name_taken") return again("Another staff account already signs in with that name. Pick a different one.", 409);
  if (outcome !== "ok") throw new ApiError(404, "not_found");
  await db.batch([
    db.prepare("DELETE FROM login_attempts WHERE rowid = ? AND key = ?").bind(res[1].meta.last_row_id, ipKey),
    // Any other outstanding links for this admin die with this one.
    db.prepare("DELETE FROM admin_setup_tokens WHERE user_id = ? AND used_at IS NULL").bind(admin.id),
    // A fresh password ends every earlier portal session of this admin.
    db.prepare("DELETE FROM sessions WHERE user_id = ?").bind(admin.id),
  ]);
  await startSession(c, admin.id);
  return c.redirect("/portal", 302);
});

portalApp.post("/portal/logout", async (c) => {
  const id = getCookie(c, SESSION_COOKIE);
  if (id) {
    await c.env.DB.prepare("DELETE FROM sessions WHERE id_hash = ?").bind(await sha256Hex(id)).run();
  }
  deleteCookie(c, SESSION_COOKIE, { path: "/" });
  return c.redirect("/portal/login", 302);
});
