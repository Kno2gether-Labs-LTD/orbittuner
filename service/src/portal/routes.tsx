import { Hono } from "hono";
import type { Context, MiddlewareHandler } from "hono";
import type { AppEnv } from "../auth/devices";
import { createPairingCode } from "../auth/devices";
import { field, portalForm, requireAdmin, requireOwner } from "../auth/portal";
import { validateSong } from "../chordpro/parse";
import { ApiError } from "../lib/errors";
import {
  findVisibleDuplicate, getFacets, getSong, listSongsForOwner, setSongStatus, upsertSong, type Song,
} from "../songs/repo";
import { seedSongs } from "../seed";
import { exportJson, exportZip, importFiles, MAX_ENTRIES, readMaxSongs, TooManySongsError, type ImportResult } from "../transfer/zip";
import { ASSETS, Shell, type NavKey } from "./Layout";
import { render, type PageStatus } from "./render";
import { SongsPage, type SongsQuery } from "./Songs";
import { DIFFICULTIES, EditorPage, type EditorValues } from "./Editor";
import { REVIEW_TABS, ReviewPage, type FlaggedSong, type HiddenSong, type ReviewCounts, type ReviewSong, type ReviewTab } from "./Review";
import { PairPage } from "./Pair";
import { DevicesPage, type DeviceRow } from "./Devices";
import { parseSttOrder } from "../voice/transcribe";
import { NUM_SETTINGS, PortalLoginLinkPage, SettingsPage, UsersPage, usersHref, type SettingsValues } from "./Users";
import { StatStrip } from "./Layout";
import {
  canManage, listUsers, MAX_CLIENT_IDS, parseClientIds, parseExpiry, PRIVATE_SONG_SQL, USER_KINDS, userCounts,
  type UserAction, type UserKind,
} from "./staff";
import { utcDay } from "../auth/plans";
import { ADMIN_SETUP_TTL_MS } from "../auth/portal";
import { randomToken, sha256Hex } from "../lib/crypto";
import { asRole } from "../auth/roles";
import { TransferPage } from "./Transfer";
import { AiKeysPage, BUILTIN_PROVIDERS, LIMITS, type AiPageProps } from "./AiKeys";
import { getProviders, PROVIDER_ID_RE, saveProviders, type ProviderConfig } from "../ai/providers";
import { deleteSecret, secretStatus, secretsEnabled, setSecret } from "../lib/secrets";
import { loadLlm } from "../ai/lazy";
import { createLinkJob, getJob, parseUrls, type JobView } from "../ai/jobs";
import { DEFAULT_SEARCH_ACTOR } from "../ai/discover";

export const portalPages = new Hono<AppEnv>();

type C = Context<AppEnv>;
const STATUSES = new Set<Song["status"]>(["published", "review", "hidden"]);
const MAX_CATEGORIES = 50;
const MAX_CATEGORY_CHARS = 100;

// ---------- static assets (public: the sign-in page uses them too) ----------

function asset(c: C, a: { body: string; version: string; type: string }) {
  const immutable = c.req.query("v") === a.version;
  return c.body(a.body, 200, {
    "content-type": a.type,
    "cache-control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
    "x-content-type-options": "nosniff",
  });
}
portalPages.get("/portal/assets/portal.css", (c) => asset(c, ASSETS.css));
portalPages.get("/portal/assets/portal.js", (c) => asset(c, ASSETS.js));

// ---------- everything else needs a portal session ----------

const PUBLIC = new Set(["/portal/setup", "/portal/login", "/portal/logout", "/portal/admin-setup"]);
/**
 * Pages an admin may use: the library, the song editor, moderation, users (each action is checked
 * again by canManage) and their own developer device. The rest is owner-only.
 */
export function adminPortalPath(path: string): boolean {
  return (
    path === "/portal" ||
    path === "/portal/review" ||
    path === "/portal/users" ||
    path === "/portal/pair" ||
    path.startsWith("/portal/users/") ||
    path.startsWith("/portal/songs/") ||
    path.startsWith("/portal/reports/")
  );
}
const portalGuard: MiddlewareHandler<AppEnv> = async (c, next) => {
  if (PUBLIC.has(c.req.path) || c.req.path.startsWith("/portal/assets/")) return next();
  return adminPortalPath(c.req.path) ? requireAdmin(c, next) : requireOwner(c, next);
};
portalPages.use("/portal/*", portalGuard);

/**
 * A song needs a moderation decision when it has flags newer than the last staff decision, or
 * open (unresolved) reports. Songs in review are listed under In review instead.
 */
const NEEDS_DECISION = `s.status != 'review' AND (
  EXISTS (SELECT 1 FROM song_flags f WHERE f.song_id = s.id AND f.created_at > COALESCE(s.moderated_at, 0))
  OR EXISTS (SELECT 1 FROM reports r WHERE r.song_id = s.id AND r.resolved_at IS NULL))`;

async function reviewCounts(c: C): Promise<ReviewCounts> {
  const r = await c.env.DB.prepare(
    `SELECT (SELECT COUNT(*) FROM songs WHERE status = 'review') AS review,
            (SELECT COUNT(*) FROM songs s WHERE ${NEEDS_DECISION}) AS flagged,
            (SELECT COUNT(*) FROM songs WHERE status = 'hidden') AS hidden`,
  ).first<ReviewCounts>();
  return r ?? { review: 0, flagged: 0, hidden: 0 };
}

async function reviewCount(c: C): Promise<number> {
  const r = await reviewCounts(c);
  return r.review + r.flagged;
}

async function shell(c: C, title: string, active: NavKey, body: unknown, status: PageStatus = 200) {
  const node = Shell({
    title,
    active,
    user: c.var.user,
    csrf: c.var.csrf,
    reviewCount: await reviewCount(c),
    children: body as never,
  });
  return render(c, node, status);
}

/** Only same-site portal paths (with an optional plain query string) are followed after an action. */
function back(c: C, fd: FormData, fallback: string) {
  const b = field(fd, "back", 500);
  return c.redirect(/^\/portal(\/[\w\-/]*)?(\?[\w\-=&%.+]*)?$/.test(b) && !b.includes("//") ? b : fallback, 302);
}

// ---------- library ----------

portalPages.get("/portal", async (c) => {
  const status = c.req.query("status") ?? "";
  const pageN = parseInt(c.req.query("page") ?? "0", 10);
  const query: SongsQuery = {
    q: (c.req.query("q") ?? "").slice(0, 200).trim(),
    lang: (c.req.query("lang") ?? "").slice(0, 16),
    cat: (c.req.query("cat") ?? "").slice(0, MAX_CATEGORY_CHARS),
    status: STATUSES.has(status as Song["status"]) ? (status as Song["status"]) : "",
    page: Number.isFinite(pageN) && pageN > 0 ? Math.min(pageN, 100_000) : 0,
  };
  const count = (k: string) => {
    const n = parseInt(c.req.query(k) ?? "", 10);
    return Number.isFinite(n) && n >= 0 && n <= 100 ? n : 0;
  };
  const seeded = c.req.query("seeded") === undefined
    ? undefined
    : { added: count("seeded"), present: count("present"), failed: count("failed") };
  const day = utcDay();
  const dayStart = Date.parse(`${day}T00:00:00Z`);
  const [list, facets, all, today, aiLimit] = await Promise.all([
    listSongsForOwner(c.env.DB, {
      q: query.q || undefined,
      lang: query.lang || undefined,
      cat: query.cat || undefined,
      status: query.status || undefined,
      page: query.page,
    }),
    getFacets(c.env.DB, c.var.user),
    c.env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>(),
    c.env.DB.prepare(
      `SELECT (SELECT COUNT(*) FROM songs WHERE created_at >= ?) AS added,
              (SELECT COUNT(*) FROM users WHERE last_seen_at >= ?) AS users,
              COALESCE((SELECT count FROM ai_usage WHERE day = ?), 0) AS ai`,
    ).bind(dayStart, dayStart, day).first<{ added: number; users: number; ai: number }>(),
    intSetting(c, "ai_daily_limit", 100),
  ]);
  const t = today ?? { added: 0, users: 0, ai: 0 };
  const stats = (
    <StatStrip
      label="Today"
      stats={[
        { key: "songs", label: "Songs in the library", value: all?.n ?? 0 },
        { key: "added", label: "Added today", value: t.added },
        { key: "users", label: "People today", value: t.users },
        { key: "ai", label: "AI used today", value: t.ai, of: aiLimit, note: "UTC day, all accounts", tone: t.ai >= aiLimit * 0.9 ? "warn" : undefined },
      ]}
    />
  );
  return shell(
    c,
    "Library",
    "library",
    <SongsPage query={query} items={list.items} total={list.total} nextPage={list.nextPage} facets={facets} libraryTotal={all?.n ?? 0} csrf={c.var.csrf} seeded={seeded} stats={stats} />,
  );
});

// ---------- editor ----------

function editorValues(fd: FormData): EditorValues {
  const status = field(fd, "status");
  const difficulty = field(fd, "difficulty");
  return {
    chordpro: field(fd, "chordpro", 1_000_000).replace(/\r\n|\r/g, "\n"),
    language: field(fd, "language", 16).trim() || "en",
    categories: field(fd, "categories", 6_000),
    difficulty: (DIFFICULTIES as readonly string[]).includes(difficulty) ? difficulty : "",
    status: STATUSES.has(status as Song["status"]) ? (status as Song["status"]) : "published",
  };
}

function parseCategories(s: string): string[] | null {
  const cats = [...new Set(s.split(",").map((x) => x.trim()).filter(Boolean))];
  if (cats.length > MAX_CATEGORIES || cats.some((x) => x.length > MAX_CATEGORY_CHARS)) return null;
  return cats;
}

const ERRORS: Record<string, string> = {
  empty: "The song is empty. Add a {title: …} line and some lyrics.",
  missing_title: "Every song needs a title. Add a line like {title: Amazing Grace} at the top.",
  too_large: "This song is too long to save (200,000 characters at most).",
  categories: `Too many categories. Use at most ${MAX_CATEGORIES}, each under ${MAX_CATEGORY_CHARS} characters.`,
};

async function saveSong(c: C, existing: Song | null) {
  const fd = await portalForm(c);
  const v = editorValues(fd);
  const again = (error: string, status: PageStatus, duplicateId?: string) =>
    shell(
      c,
      existing?.title ?? "New song",
      "library",
      <EditorPage csrf={c.var.csrf} song={existing ?? undefined} values={v} error={error} duplicateId={duplicateId} />,
      status,
    );
  const valid = validateSong(v.chordpro);
  if (!valid.ok) return again(ERRORS[valid.error], valid.error === "too_large" ? 413 : 422);
  const categories = parseCategories(v.categories);
  if (!categories) return again(ERRORS.categories, 400);
  const artist = valid.doc.meta.artist?.trim() || null;
  const title = valid.doc.meta.title.trim();
  const dup = await findVisibleDuplicate(c.env.DB, c.var.user, artist, title);
  if (dup && dup !== existing?.id) {
    return again(`“${title}”${artist ? ` by ${artist}` : ""} is already in the library.`, 409, dup);
  }
  const song = await upsertSong(c.env.DB, {
    id: existing?.id,
    chordpro: v.chordpro,
    language: v.language,
    categories,
    difficulty: v.difficulty || undefined,
    source: existing?.source ?? "owner",
    status: v.status,
    createdBy: c.var.user.id,
    // An admin save of an existing song is a moderation decision: pin it against auto-hiding.
    moderatedBy: existing ? c.var.user.id : undefined,
  });
  return c.redirect(`/portal/songs/${encodeURIComponent(song.id)}`, 302);
}

portalPages.get("/portal/songs/new", (c) =>
  shell(c, "New song", "library", <EditorPage csrf={c.var.csrf} />),
);
portalPages.post("/portal/songs/new", (c) => saveSong(c, null));

async function songOr404(c: C): Promise<Song> {
  const song = await getSong(c.env.DB, c.var.user, c.req.param("id") ?? "");
  if (!song) throw new ApiError(404, "not_found");
  return song;
}

portalPages.get("/portal/songs/:id", async (c) => {
  const song = await songOr404(c);
  return shell(c, song.title, "library", <EditorPage csrf={c.var.csrf} song={song} />);
});
portalPages.post("/portal/songs/:id", async (c) => saveSong(c, await songOr404(c)));

portalPages.post("/portal/songs/:id/status", async (c) => {
  const fd = await portalForm(c);
  const status = field(fd, "status") as Song["status"];
  if (!STATUSES.has(status)) throw new ApiError(400, "invalid_input");
  const song = await songOr404(c);
  // An admin decision: pins the song so flags no longer auto-hide it, and settles its open reports.
  await setSongStatus(c.env.DB, song.id, status, c.var.user.id);
  await c.env.DB.prepare("UPDATE reports SET resolved_at = ? WHERE song_id = ? AND resolved_at IS NULL")
    .bind(Date.now(), song.id).run();
  return back(c, fd, `/portal/songs/${encodeURIComponent(song.id)}`);
});

/**
 * Delete a song for good, with its versions, search row, flags, reports, and the favourites and
 * playlist entries that point at it. Only songs other
 * users cannot see (in review or hidden): a published song is hidden first, so devices that
 * synced it learn of the change. 409 song_published otherwise.
 */
portalPages.post("/portal/songs/:id/delete", async (c) => {
  const fd = await portalForm(c);
  const song = await songOr404(c);
  if (song.status === "published") throw new ApiError(409, "song_published");
  const db = c.env.DB;
  const [del] = await db.batch([
    db.prepare("DELETE FROM songs WHERE id = ? AND status != 'published'").bind(song.id),
    db.prepare("DELETE FROM songs_fts WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
    db.prepare("DELETE FROM song_versions WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
    db.prepare("DELETE FROM song_flags WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
    db.prepare("DELETE FROM reports WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
    db.prepare("DELETE FROM favourites WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
    // Playlists that held the song change: bump their server change marker before the items go.
    db.prepare(
      // Same rule as playlist writes: past every marker this user's devices may already hold.
      `UPDATE playlists SET modified_at = max(?, COALESCE((SELECT MAX(p2.modified_at) FROM playlists p2 WHERE p2.user_id = playlists.user_id), 0) + 1)
        WHERE id IN (SELECT playlist_id FROM playlist_items WHERE song_id = ?) AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)`,
    ).bind(Date.now(), song.id, song.id),
    db.prepare("DELETE FROM playlist_items WHERE song_id = ? AND NOT EXISTS (SELECT 1 FROM songs WHERE id = ?)").bind(song.id, song.id),
  ]);
  if (!del.meta.changes) throw new ApiError(409, "song_published");
  return back(c, fd, "/portal/review?tab=hidden");
});

// ---------- review ----------

const REVIEW_LIMIT = 200;

async function flaggedSongs(c: C): Promise<FlaggedSong[]> {
  const db = c.env.DB;
  const [songs, reasons, notes] = await db.batch([
    db.prepare(
      `SELECT s.id, s.title, s.artist, s.status,
              (SELECT COUNT(*) FROM song_flags f WHERE f.song_id = s.id AND f.created_at > COALESCE(s.moderated_at, 0)) AS flags,
              (SELECT COUNT(*) FROM reports r WHERE r.song_id = s.id AND r.resolved_at IS NULL) AS reports,
              MAX(COALESCE((SELECT MAX(f.created_at) FROM song_flags f WHERE f.song_id = s.id), 0),
                  COALESCE((SELECT MAX(r.created_at) FROM reports r WHERE r.song_id = s.id AND r.resolved_at IS NULL), 0)) AS last_at
         FROM songs s WHERE ${NEEDS_DECISION}
        ORDER BY last_at DESC, s.id LIMIT ?`,
    ).bind(REVIEW_LIMIT),
    db.prepare(
      `SELECT f.song_id, f.reason, COUNT(*) AS n FROM song_flags f JOIN songs s ON s.id = f.song_id
        WHERE f.created_at > COALESCE(s.moderated_at, 0) AND ${NEEDS_DECISION}
        GROUP BY f.song_id, f.reason ORDER BY n DESC, f.reason`,
    ),
    // Notes: the flagger's own words; legacy reports (no flag row) keep their report note.
    db.prepare(
      `SELECT song_id, note FROM (
         SELECT f.song_id, f.note, f.created_at FROM song_flags f JOIN songs s ON s.id = f.song_id
          WHERE f.note IS NOT NULL AND f.note != '' AND f.created_at > COALESCE(s.moderated_at, 0) AND ${NEEDS_DECISION}
         UNION ALL
         SELECT r.song_id, r.note, r.created_at FROM reports r
          WHERE r.resolved_at IS NULL AND r.note IS NOT NULL AND r.note != ''
            AND NOT EXISTS (SELECT 1 FROM song_flags f WHERE f.song_id = r.song_id AND f.user_id = r.user_id)
       ) ORDER BY created_at DESC LIMIT 1000`,
    ),
  ]);
  const bySong = new Map<string, FlaggedSong>();
  for (const r of songs.results as Omit<FlaggedSong, "reasons" | "notes">[]) bySong.set(r.id, { ...r, reasons: [], notes: [] });
  for (const r of reasons.results as { song_id: string; reason: string; n: number }[]) {
    bySong.get(r.song_id)?.reasons.push({ reason: r.reason, n: r.n });
  }
  for (const r of notes.results as { song_id: string; note: string }[]) {
    const f = bySong.get(r.song_id);
    if (f && f.notes.length < 3) f.notes.push(r.note);
  }
  return [...bySong.values()];
}

portalPages.get("/portal/review", async (c) => {
  const counts = await reviewCounts(c);
  const asked = c.req.query("tab") as ReviewTab | undefined;
  // No tab asked for: open the first queue with work in it.
  const tab: ReviewTab = asked && REVIEW_TABS.includes(asked)
    ? asked
    : counts.review > 0 ? "review" : counts.flagged > 0 ? "flagged" : "review";
  const db = c.env.DB;
  let body;
  if (tab === "review") {
    const { results } = await db.prepare(
      `SELECT s.id, s.title, s.artist, s.chordpro, s.source, s.updated_at,
              COALESCE(u.email, CASE WHEN u.role IN ('owner', 'admin') THEN u.name END) AS by
         FROM songs s LEFT JOIN users u ON u.id = s.created_by
        WHERE s.status = 'review' ORDER BY s.updated_at DESC, s.id LIMIT ?`,
    ).bind(REVIEW_LIMIT).all<ReviewSong>();
    body = <ReviewPage csrf={c.var.csrf} tab={tab} counts={counts} songs={results} />;
  } else if (tab === "flagged") {
    body = <ReviewPage csrf={c.var.csrf} tab={tab} counts={counts} flagged={await flaggedSongs(c)} />;
  } else {
    const { results } = await db.prepare(
      `SELECT s.id, s.title, s.artist, s.updated_at, s.moderated_at,
              (SELECT COUNT(*) FROM song_flags f WHERE f.song_id = s.id) AS flags
         FROM songs s WHERE s.status = 'hidden' ORDER BY s.updated_at DESC, s.id LIMIT ?`,
    ).bind(REVIEW_LIMIT).all<HiddenSong>();
    body = <ReviewPage csrf={c.var.csrf} tab={tab} counts={counts} hidden={results} />;
  }
  return shell(c, "Review", "review", body);
});

portalPages.post("/portal/reports/:id/resolve", async (c) => {
  const fd = await portalForm(c);
  const r = await c.env.DB.prepare("UPDATE reports SET resolved_at = ? WHERE id = ? AND resolved_at IS NULL")
    .bind(Date.now(), c.req.param("id"))
    .run();
  if (!r.meta.changes) throw new ApiError(404, "not_found");
  return back(c, fd, "/portal/review");
});

// ---------- developer device, devices ----------

/** Developer device: a pairing code for the signed-in staff account itself (owner or admin). */
const pairPage = (c: C, result?: { code: string; expiresAt: number }) =>
  shell(
    c,
    "Developer device",
    "pair",
    <PairPage csrf={c.var.csrf} who={{ name: c.var.user.name, role: c.var.user.role }} host={c.env.PUBLIC_HOST} result={result} />,
  );

portalPages.get("/portal/pair", (c) => pairPage(c));

portalPages.post("/portal/pair", async (c) => {
  await portalForm(c);
  const { code, expiresAt } = await createPairingCode(c.env, c.var.user.id);
  return pairPage(c, { code, expiresAt });
});

portalPages.get("/portal/devices", async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked_at, u.name AS user_name, u.role
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
      ORDER BY d.revoked_at IS NOT NULL, d.last_seen_at DESC, d.id LIMIT 500`,
  ).all<DeviceRow>();
  return shell(c, "Devices", "devices", <DevicesPage csrf={c.var.csrf} devices={results} />);
});

portalPages.post("/portal/devices/:id/revoke", async (c) => {
  const fd = await portalForm(c);
  const r = await c.env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
    .bind(Date.now(), c.req.param("id"))
    .run();
  if (!r.meta.changes) throw new ApiError(404, "not_found");
  return back(c, fd, "/portal/devices");
});

// ---------- users ----------

function usersQuery(c: C) {
  const kind = c.req.query("kind") ?? "all";
  const pageN = parseInt(c.req.query("page") ?? "0", 10);
  return {
    q: (c.req.query("q") ?? "").slice(0, 200).trim(),
    kind: ((USER_KINDS as readonly string[]).includes(kind) ? kind : "all") as UserKind,
    page: Number.isFinite(pageN) && pageN > 0 ? Math.min(pageN, 100_000) : 0,
  };
}

portalPages.get("/portal/users", async (c) => {
  const query = usersQuery(c);
  const [list, counts] = await Promise.all([listUsers(c.env.DB, query), userCounts(c.env.DB)]);
  return shell(
    c,
    "Users",
    "users",
    <UsersPage csrf={c.var.csrf} actor={c.var.user} query={query} items={list.items} total={list.total} hasNext={list.hasNext} counts={counts} back={usersHref(query)} />,
  );
});

/** The target account, after checking the signed-in staff member may do `action` to it (403 otherwise). */
async function target(c: C, action: UserAction) {
  const row = await c.env.DB.prepare("SELECT id, role, google_sub IS NOT NULL AS linked FROM users WHERE id = ?")
    .bind(c.req.param("id") ?? "")
    .first<{ id: string; role: string | null; linked: number }>();
  if (!row) throw new ApiError(404, "not_found");
  const t = { id: row.id, role: asRole(row.role), linked: row.linked === 1 };
  if (!canManage(c.var.user, t, action)) throw new ApiError(403, "forbidden");
  return t;
}

portalPages.post("/portal/users/:id/plan", async (c) => {
  const fd = await portalForm(c);
  const t = await target(c, "plan");
  const plan = field(fd, "plan", 10);
  if (plan !== "free" && plan !== "plus") throw new ApiError(400, "invalid_input");
  const expires = plan === "plus" ? parseExpiry(field(fd, "expires", 20)) : null;
  if (expires === undefined) throw new ApiError(400, "invalid_expiry");
  await c.env.DB.prepare("UPDATE users SET plan = ?, plan_expires_at = ? WHERE id = ?").bind(plan, expires, t.id).run();
  return back(c, fd, "/portal/users");
});

portalPages.post("/portal/users/:id/role", async (c) => {
  const fd = await portalForm(c);
  const t = await target(c, "role");
  const role = field(fd, "role", 10);
  if (role !== "admin" && role !== "user") throw new ApiError(400, "invalid_input");
  // Admins are accountable people: only a Google-signed-in account can be promoted.
  if (role === "admin" && !t.linked) throw new ApiError(400, "needs_google");
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE users SET role = ? WHERE id = ? AND role != 'owner'").bind(role, t.id),
    // Setup links belong to the role they were made for.
    c.env.DB.prepare("DELETE FROM admin_setup_tokens WHERE user_id = ? AND used_at IS NULL").bind(t.id),
  ]);
  return back(c, fd, "/portal/users");
});

portalPages.post("/portal/users/:id/access", async (c) => {
  const fd = await portalForm(c);
  const t = await target(c, "access");
  const action = field(fd, "action", 10);
  if (action !== "disable" && action !== "enable") throw new ApiError(400, "invalid_input");
  const db = c.env.DB;
  if (action === "disable") {
    // Device tokens are refused (403 account_disabled) from the next request; portal sessions end now.
    await db.batch([
      db.prepare("UPDATE users SET disabled_at = ? WHERE id = ? AND disabled_at IS NULL AND role != 'owner'").bind(Date.now(), t.id),
      db.prepare("DELETE FROM sessions WHERE user_id = ?").bind(t.id),
    ]);
  } else {
    await db.prepare("UPDATE users SET disabled_at = NULL WHERE id = ?").bind(t.id).run();
  }
  return back(c, fd, "/portal/users");
});

/**
 * Owner only: a one-time link (24 h) that lets an admin set their portal sign-in name and password.
 * Only the token's SHA-256 is stored; making a new link voids the admin's earlier unused ones.
 */
portalPages.post("/portal/users/:id/portal-login", async (c) => {
  await portalForm(c);
  if (c.var.user.role !== "owner") throw new ApiError(403, "forbidden");
  const row = await c.env.DB.prepare("SELECT id, name, role, email, password_hash IS NOT NULL AS has_password FROM users WHERE id = ?")
    .bind(c.req.param("id") ?? "")
    .first<{ id: string; name: string; role: string | null; email: string | null; has_password: number }>();
  if (!row) throw new ApiError(404, "not_found");
  if (row.role === "owner") throw new ApiError(403, "forbidden");
  if (row.role !== "admin") throw new ApiError(400, "not_admin");
  const token = randomToken(32);
  const now = Date.now();
  const expiresAt = now + ADMIN_SETUP_TTL_MS;
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM admin_setup_tokens WHERE user_id = ? AND used_at IS NULL").bind(row.id),
    c.env.DB.prepare(
      "INSERT INTO admin_setup_tokens (token_hash, user_id, created_by, created_at, expires_at, used_at) VALUES (?, ?, ?, ?, ?, NULL)",
    ).bind(await sha256Hex(token), row.id, c.var.user.id, now, expiresAt),
  ]);
  const link = `${new URL(c.req.url).origin}/portal/admin-setup?token=${token}`;
  return shell(
    c,
    "Portal sign-in link",
    "users",
    <PortalLoginLinkPage who={row.email ?? row.name} link={link} expiresAt={expiresAt} replacing={row.has_password === 1} />,
  );
});

portalPages.post("/portal/users/:id/revoke-devices", async (c) => {
  const fd = await portalForm(c);
  const t = await target(c, "devices");
  await c.env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").bind(Date.now(), t.id).run();
  return back(c, fd, "/portal/users");
});

// ---------- import / export ----------

type TransferExtra = { result?: ImportResult; error?: string; job?: JobView; linksError?: string; links?: string };

async function transferPage(c: C, extra: TransferExtra = {}, status: PageStatus = 200) {
  const [count, maxSongs] = await Promise.all([
    c.env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>(),
    readMaxSongs(c.env.DB),
  ]);
  return shell(
    c,
    "Import & export",
    "transfer",
    <TransferPage csrf={c.var.csrf} songCount={count?.n ?? 0} maxSongs={maxSongs} {...extra} />,
    status,
  );
}

portalPages.get("/portal/transfer", (c) => transferPage(c));

// No-script fallback for the import form (the page script uploads in chunks via /v1/import).
portalPages.post("/portal/transfer", async (c) => {
  const fd = await portalForm(c);
  const files: { name: string; data: Uint8Array }[] = [];
  for (const [k, v] of fd.entries()) {
    if (k === "_csrf" || typeof v === "string") continue;
    if (files.length >= MAX_ENTRIES) return transferPage(c, { error: "Too many files in one upload." }, 413);
    if (v.size === 0 && !v.name) continue;
    files.push({ name: v.name || "upload", data: new Uint8Array(await v.arrayBuffer()) });
  }
  if (files.length === 0) return transferPage(c, { error: "Choose at least one file to import." }, 400);
  const aiField = field(fd, "ai", 10);
  const ai = aiField === "auto" || aiField === "on" ? aiField : "off";
  try {
    const result = await importFiles(c.env.DB, c.var.user, files, { ai, env: c.env });
    if (result.failed.some((f) => f.error === "archive_too_large")) {
      return transferPage(c, { result, error: "That upload is too large. Nothing was imported; split it into smaller archives." }, 413);
    }
    return transferPage(c, { result });
  } catch (e) {
    if (e instanceof TooManySongsError) {
      return transferPage(c, { error: `That upload holds more than ${e.max} songs. Nothing was imported; split it into archives of at most ${e.max} songs.` }, 413);
    }
    throw e;
  }
});

// No-script fallback for the links form (the page script posts to /v1/import/links and polls).
portalPages.post("/portal/transfer/links", async (c) => {
  const fd = await portalForm(c);
  const raw = field(fd, "urls", 500_000);
  const lines = raw.split(/\r\n|\r|\n/).map((l) => l.trim()).filter(Boolean);
  const again = (linksError: string, status: PageStatus) => transferPage(c, { linksError, links: raw.slice(0, 50_000) }, status);
  if (lines.length === 0) return again("Paste at least one link, one per line.", 400);
  let urls: string[];
  try {
    urls = parseUrls(lines);
  } catch {
    return again("Paste at most 200 links, one per line, each under 2,048 characters.", 400);
  }
  try {
    const id = await createLinkJob(c.env, c.var.user, urls);
    return c.redirect(`/portal/transfer/jobs/${encodeURIComponent(id)}`, 302);
  } catch (e) {
    if (e instanceof ApiError && e.code === "queue_unavailable") {
      return again("The import queue is unavailable right now. Nothing was queued; try again in a minute.", 503);
    }
    throw e;
  }
});

portalPages.get("/portal/transfer/jobs/:id", async (c) => {
  const job = await getJob(c.env.DB, c.var.user, c.req.param("id"));
  if (!job) throw new ApiError(404, "not_found");
  return transferPage(c, { job });
});

portalPages.get("/portal/export.zip", async (c) => {
  const bytes = await exportZip(c.env.DB, c.var.user);
  return new Response(bytes, {
    headers: {
      "content-type": "application/zip",
      "content-disposition": `attachment; filename="orbittuner-songs-${new Date().toISOString().slice(0, 10)}.zip"`,
      "cache-control": "no-store",
    },
  });
});

portalPages.get("/portal/backup.json", async (c) =>
  new Response(await exportJson(c.env.DB), {
    headers: {
      "content-type": "application/json",
      "content-disposition": `attachment; filename="orbittuner-backup-${new Date().toISOString().slice(0, 10)}.json"`,
      "cache-control": "no-store",
    },
  }),
);

// ---------- sample songs ----------

// Idempotent: importFiles skips songs already visible (duplicate check), so repeats add 0.
portalPages.post("/portal/seed", async (c) => {
  await portalForm(c);
  const r = await seedSongs(c.env.DB, c.var.user);
  return c.redirect(`/portal?seeded=${r.added}&present=${r.duplicates.length}&failed=${r.failed.length}`, 302);
});

// ---------- AI & keys ----------

async function intSetting(c: C, key: string, fallback: number): Promise<number> {
  const r = await c.env.DB.prepare("SELECT value FROM settings WHERE key = ?").bind(key).first<{ value: string | null }>();
  const n = parseInt(r?.value ?? "", 10);
  return Number.isFinite(n) ? n : fallback;
}

async function putSetting(c: C, key: string, value: string) {
  await c.env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
    .bind(key, value)
    .run();
}

const DEFAULT_APIFY_ACTOR = "apify~rag-web-browser";
const APIFY_ACTOR_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}~[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/;

const SAVED: Record<string, string> = {
  gateways: "Gateway settings saved.",
  key: "Key saved. Only its last four characters are kept in view.",
  removed: "Key removed. The deploy-time key, if any, is used again.",
  limits: "Limits saved.",
  apify: "Apify actor saved.",
};

async function aiPage(c: C, extra: Pick<AiPageProps, "error"> & { providers?: ProviderConfig[] } = {}, status: PageStatus = 200) {
  const day = new Date().toISOString().slice(0, 10);
  const [providers, keys, dailyLimit, maxTokens, usage, actor, searchActor] = await Promise.all([
    extra.providers ?? getProviders(c.env),
    secretStatus(c.env),
    intSetting(c, "ai_daily_limit", 100),
    intSetting(c, "ai_max_output_tokens", 4000),
    c.env.DB.prepare("SELECT count FROM ai_usage WHERE day = ?").bind(day).first<{ count: number }>(),
    c.env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_actor'").first<{ value: string | null }>(),
    c.env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_search_actor'").first<{ value: string | null }>(),
  ]);
  const saved = status === 200 ? SAVED[c.req.query("saved") ?? ""] : undefined;
  return shell(
    c,
    "AI & keys",
    "ai",
    <AiKeysPage
      csrf={c.var.csrf}
      providers={providers}
      keys={keys}
      secretsOn={secretsEnabled(c.env)}
      dailyLimit={dailyLimit}
      maxTokens={maxTokens}
      usage={usage?.count ?? 0}
      apifyActor={actor?.value?.trim() || DEFAULT_APIFY_ACTOR}
      apifySearchActor={searchActor?.value?.trim() || DEFAULT_SEARCH_ACTOR}
      notice={saved}
      error={extra.error}
    />,
    status,
  );
}

portalPages.get("/portal/ai", (c) => aiPage(c));

portalPages.post("/portal/ai/providers", async (c) => {
  const fd = await portalForm(c);
  const current = await getProviders(c.env);
  const byId = new Map(current.map((p) => [p.id, p]));
  const order = fd.getAll("order").map(String);
  // The form must name every current gateway exactly once (a stale tab after a change elsewhere does not).
  if (order.length !== current.length || new Set(order).size !== order.length || !order.every((id) => byId.has(id))) {
    return aiPage(c, { error: { section: "gateways", text: "The gateway list changed in another tab. Reload and try again." } }, 409);
  }
  let list: ProviderConfig[] = order.map((id) => {
    const p = byId.get(id)!;
    return {
      ...p,
      enabled: fd.get(`enabled_${id}`) === "on",
      model: field(fd, `model_${id}`, 200).trim(),
      baseUrl: BUILTIN_PROVIDERS.has(id) ? p.baseUrl : field(fd, `base_${id}`, 500).trim(),
    };
  });
  const move = /^([a-z0-9_-]{1,32}):(up|down)$/.exec(field(fd, "move", 50));
  if (move) {
    const i = list.findIndex((p) => p.id === move[1]);
    const j = move[2] === "up" ? i - 1 : i + 1;
    if (i >= 0 && j >= 0 && j < list.length) {
      list = list.slice();
      [list[i], list[j]] = [list[j], list[i]];
    }
  }
  try {
    await saveProviders(c.env, list);
  } catch (e) {
    if (!(e instanceof ApiError) || e.code !== "invalid_providers") throw e;
    return aiPage(
      c,
      {
        providers: list,
        error: { section: "gateways", text: "An enabled gateway needs a model, and a custom gateway needs an https:// base URL. Nothing was saved." },
      },
      400,
    );
  }
  return c.redirect("/portal/ai?saved=gateways#gateways", 302);
});

portalPages.post("/portal/ai/keys", async (c) => {
  const fd = await portalForm(c);
  const name = field(fd, "name", 64);
  const intent = field(fd, "intent", 10);
  const fail = (text: string, status: PageStatus) => aiPage(c, { error: { section: "keys", text } }, status);
  try {
    if (intent === "remove") {
      await deleteSecret(c.env, name);
      return c.redirect("/portal/ai?saved=removed#keys", 302);
    }
    if (intent !== "save") return fail("Choose Save or Remove.", 400);
    // Never echo the value back: errors re-render the page with an empty field.
    await setSecret(c.env, name, field(fd, "value", 4200));
    return c.redirect("/portal/ai?saved=key#keys", 302);
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    if (e.code === "invalid_name") return fail("That is not a key the library knows about.", 400);
    if (e.code === "invalid_value") return fail("Keys are 8 to 4,096 characters long. Paste the whole key and try again.", 400);
    if (e.code === "secrets_disabled") return fail("Keys cannot be saved until SECRETS_KEY is set on the Worker.", 503);
    throw e;
  }
});

portalPages.post("/portal/ai/limits", async (c) => {
  const fd = await portalForm(c);
  const num = (k: string, r: { min: number; max: number }) => {
    const raw = field(fd, k, 20).trim();
    const n = /^\d{1,6}$/.test(raw) ? parseInt(raw, 10) : NaN;
    return Number.isFinite(n) && n >= r.min && n <= r.max ? n : null;
  };
  const daily = num("ai_daily_limit", LIMITS.daily);
  const tokens = num("ai_max_output_tokens", LIMITS.tokens);
  if (daily === null || tokens === null) {
    const text = `Conversions per day must be a whole number from ${LIMITS.daily.min} to ${LIMITS.daily.max.toLocaleString("en")}, and tokens from ${LIMITS.tokens.min} to ${LIMITS.tokens.max.toLocaleString("en")}.`;
    return aiPage(c, { error: { section: "limits", text } }, 400);
  }
  await putSetting(c, "ai_daily_limit", String(daily));
  await putSetting(c, "ai_max_output_tokens", String(tokens));
  return c.redirect("/portal/ai?saved=limits#limits", 302);
});

portalPages.post("/portal/ai/apify", async (c) => {
  const fd = await portalForm(c);
  const actor = field(fd, "apify_actor", 300).trim();
  // The search actor is optional in the post (older forms only carry apify_actor).
  const search = fd.has("apify_search_actor") ? field(fd, "apify_search_actor", 300).trim() : null;
  if (!APIFY_ACTOR_RE.test(actor) || (search !== null && !APIFY_ACTOR_RE.test(search))) {
    return aiPage(c, { error: { section: "apify", text: "Use the form user~actor, for example apify~rag-web-browser. Nothing was saved." } }, 400);
  }
  await putSetting(c, "apify_actor", actor);
  if (search !== null) await putSetting(c, "apify_search_actor", search);
  return c.redirect("/portal/ai?saved=apify#apify", 302);
});

// JSON for the page script (owner session + X-CSRF via the portal CSRF guard).
portalPages.post("/portal/ai/test/:id", async (c) => {
  const id = c.req.param("id");
  if (!PROVIDER_ID_RE.test(id)) return c.json({ error: "not_found" }, 404);
  const { testProvider } = await loadLlm();
  const r = await testProvider(c.env, id);
  c.header("Cache-Control", "no-store");
  return c.json(r);
});

// ---------- settings ----------

async function settingsValues(c: C): Promise<SettingsValues> {
  const keys = [...NUM_SETTINGS.map((n) => n.key), "google_client_ids", "stt_order", "inworld_stt_model"];
  const { results } = await c.env.DB.prepare("SELECT key, value FROM settings WHERE key IN (SELECT value FROM json_each(?))")
    .bind(JSON.stringify(keys)).all<{ key: string; value: string | null }>();
  const got = new Map(results.map((r) => [r.key, r.value ?? ""]));
  const v = {} as SettingsValues;
  for (const n of NUM_SETTINGS) {
    const x = parseInt(got.get(n.key) ?? "", 10);
    v[n.key] = String(Number.isFinite(x) ? x : n.fallback);
  }
  v.stt_order = got.get("stt_order") ?? "";
  v.inworld_stt_model = got.get("inworld_stt_model") ?? "";
  let ids: string[] = [];
  try {
    const parsed = JSON.parse(got.get("google_client_ids") || "[]");
    if (Array.isArray(parsed)) ids = parsed.filter((x): x is string => typeof x === "string");
  } catch {
    /* malformed: show empty */
  }
  v.google_client_ids = ids.join("\n");
  return v;
}

const PRIVATE_SAVED: Record<string, string> = {
  "1": "Settings saved.",
  hidden: "Private songs hidden. Only staff can see them now.",
  published: "Private songs published.",
};

async function settingsPage(c: C, extra: { values?: SettingsValues; error?: string } = {}, status: PageStatus = 200) {
  const [values, priv] = await Promise.all([
    extra.values ?? settingsValues(c),
    c.env.DB.prepare(
      `SELECT COUNT(*) AS n, COALESCE(SUM(status = 'hidden'), 0) AS hidden, COALESCE(SUM(status = 'published'), 0) AS published
         FROM songs WHERE ${PRIVATE_SONG_SQL}`,
    ).first<{ n: number; hidden: number; published: number }>(),
  ]);
  return shell(
    c,
    "Settings",
    "settings",
    <SettingsPage
      csrf={c.var.csrf}
      values={values}
      privateCount={priv?.n ?? 0}
      privateHidden={priv?.hidden ?? 0}
      privatePublished={priv?.published ?? 0}
      error={extra.error}
      notice={status === 200 ? PRIVATE_SAVED[c.req.query("saved") ?? ""] : undefined}
    />,
    status,
  );
}

portalPages.get("/portal/settings", (c) => settingsPage(c));

portalPages.post("/portal/settings", async (c) => {
  const fd = await portalForm(c);
  const values = {} as SettingsValues;
  const bad: string[] = [];
  const nums: [string, number][] = [];
  for (const n of NUM_SETTINGS) {
    const raw = field(fd, n.key, 20).trim();
    values[n.key] = raw;
    const x = /^\d{1,6}$/.test(raw) ? parseInt(raw, 10) : NaN;
    if (!Number.isFinite(x) || x < n.min || x > n.max) bad.push(`${n.title} (${n.min}–${n.max.toLocaleString("en")})`);
    else nums.push([n.key, x]);
  }
  const sttOrder = field(fd, "stt_order", 100).trim();
  const sttModel = field(fd, "inworld_stt_model", 200).trim();
  values.stt_order = sttOrder.slice(0, 100);
  values.inworld_stt_model = sttModel.slice(0, 200);
  const rawIds = field(fd, "google_client_ids", 20_000);
  values.google_client_ids = rawIds.slice(0, 5_000);
  const ids = parseClientIds(rawIds);
  let error = "";
  if (bad.length) error = `Use whole numbers in range for: ${bad.join(", ")}.`;
  else if (sttOrder && !parseSttOrder(sttOrder)) error = "Speech-to-text order must be a comma list of workers-ai and inworld.";
  else if (sttModel && !/^[A-Za-z0-9._/:-]{1,80}$/.test(sttModel)) error = "Inworld speech model may only use letters, digits and . _ / : - (80 characters at most).";
  else if (ids.bad.length) {
    error = `Not a Google OAuth client ID: ${ids.bad.slice(0, 3).map((b) => b.slice(0, 120)).join(", ")}. Each line must end .apps.googleusercontent.com.`;
  } else if (ids.ids.length > MAX_CLIENT_IDS) error = `At most ${MAX_CLIENT_IDS} client IDs.`;
  if (error) return settingsPage(c, { values, error: `${error} Nothing was saved.` }, 400);
  const upsert = "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";
  await c.env.DB.batch([
    ...nums.map(([k, n]) => c.env.DB.prepare(upsert).bind(k, String(n))),
    c.env.DB.prepare(upsert).bind("stt_order", sttOrder),
    c.env.DB.prepare(upsert).bind("inworld_stt_model", sttModel),
    c.env.DB.prepare(upsert).bind("google_client_ids", JSON.stringify(ids.ids)),
  ]);
  return c.redirect("/portal/settings?saved=1", 302);
});

/**
 * Hide every published, or publish every hidden, staff-added song carrying the `private` category
 * (owner only). Songs in review and members' songs are never touched. Only rows whose status
 * actually changes are written: their version is bumped (devices pick the change up) and they are
 * pinned (moderated_at/by) like any staff decision, so repeated presses change nothing.
 */
portalPages.post("/portal/settings/private", async (c) => {
  const fd = await portalForm(c);
  const action = field(fd, "action", 10);
  if (action !== "hide" && action !== "publish") throw new ApiError(400, "invalid_input");
  const [from, status] = action === "hide" ? ["published", "hidden"] : ["hidden", "published"];
  const now = Date.now();
  await c.env.DB.prepare(
    `UPDATE songs SET status = ?, version = version + 1, updated_at = ?, moderated_at = ?, moderated_by = ?
      WHERE status = ? AND ${PRIVATE_SONG_SQL}`,
  ).bind(status, now, now, c.var.user.id, from).run();
  return c.redirect(`/portal/settings?saved=${status}#private`, 302);
});
