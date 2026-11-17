import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import type { Env } from "../src/env";
import { ApiError } from "../src/lib/errors";
import { hashPassword, randomToken, sha256Hex, verifyPassword } from "../src/lib/crypto";
import { redeemAdminSetup } from "../src/auth/portal";
import { upsertSong } from "../src/songs/repo";
import { AI_REQUESTS_PER_MINUTE, aiRemaining, checkAiRate, refundAi, reserveAi, reserveAiUpTo } from "../src/auth/plans";
import { canManage } from "../src/portal/staff";
import worker from "../src/index";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

type Who = Awaited<ReturnType<typeof makeUser>>;
type Session = { cookie: string; csrf: string; id: string };

const EVIL = `<script>alert(1)</script>"><img src=x onerror=alert(2)>`;
const GOOD_ID = "123456789012-abcdefghijklmnop.apps.googleusercontent.com";
const GOOD_ID_2 = "987654321098-zyxw.apps.googleusercontent.com";
const SEARCH = { search_daily_anon: "2", search_daily_free: "10", search_daily_plus: "50", search_daily_per_ip: "30", search_daily_global: "500" };
const VOICE = { voice_daily_anon: "3", voice_daily_free: "10", voice_daily_plus: "50", voice_daily_per_ip: "40", voice_daily_global: "1000" };

/** A live portal session for an existing owner/admin row (bypasses the password form). */
async function sessionFor(u: Who): Promise<Session> {
  const id = randomToken(32);
  const csrf = randomToken(32);
  await env.DB.prepare("INSERT INTO sessions (id_hash, user_id, csrf, expires_at) VALUES (?, ?, ?, ?)")
    .bind(await sha256Hex(id), u.id, csrf, Date.now() + 3_600_000).run();
  return { cookie: `ot_session=${id}`, csrf, id: u.id };
}

const get = (s: Session, path: string) => SELF.fetch(ORIGIN + path, { headers: { cookie: s.cookie }, redirect: "manual" });
const post = (s: Session, path: string, fields: Record<string, string> = {}) =>
  SELF.fetch(ORIGIN + path, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: s.cookie },
    body: new URLSearchParams({ _csrf: s.csrf, ...fields }),
    redirect: "manual",
  });
const me = (u: Who) => SELF.fetch(`${ORIGIN}/v1/me`, { headers: u.headers });
const userRow = (id: string) =>
  env.DB.prepare("SELECT role, plan, plan_expires_at, disabled_at FROM users WHERE id = ?").bind(id).first<any>();

async function linked(name = "g", email = `${name}@example.com`) {
  const u = await makeUser("user", name, { linked: true });
  await env.DB.prepare("UPDATE users SET email = ? WHERE id = ?").bind(email, u.id).run();
  return u;
}

const SONG = (title: string) => `{title: ${title}}\n{artist: Trad}\n[G]one line [C]here\n[D]two line here\n[Em]three\nfour\n`;
async function song(title: string, status: "published" | "review" | "hidden" = "published", categories: string[] = [], createdBy = "seed") {
  return upsertSong(env.DB, { chordpro: SONG(title), source: "owner", status, createdBy, categories });
}

let owner: Who, admin: Who, os: Session, as: Session;

beforeEach(async () => {
  await clearSongs();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"), env.DB.prepare("DELETE FROM sessions"),
    env.DB.prepare("DELETE FROM song_flags"), env.DB.prepare("DELETE FROM reports"),
    env.DB.prepare("DELETE FROM user_ai_usage"), env.DB.prepare("DELETE FROM ip_ai_usage"),
    env.DB.prepare("DELETE FROM ai_request_attempts"), env.DB.prepare("DELETE FROM pairing_codes"),
    env.DB.prepare("DELETE FROM login_attempts"),
    env.DB.prepare("DELETE FROM admin_setup_tokens"), env.DB.prepare("DELETE FROM playlists"), env.DB.prepare("DELETE FROM playlist_items"),
    env.DB.prepare("UPDATE settings SET value = '[]' WHERE key = 'google_client_ids'"),
    env.DB.prepare("UPDATE settings SET value = '1' WHERE key = 'anon_ai_daily'"),
  ]);
  owner = await makeUser("owner", "Owner");
  admin = await makeUser("admin", "Ada");
  await env.DB.prepare("UPDATE users SET google_sub = 'g-admin', email = 'ada@example.com' WHERE id = ?").bind(admin.id).run();
  os = await sessionFor(owner);
  as = await sessionFor(admin);
});

describe("staff permissions", () => {
  it("canManage: the owner does everything but touch their own role/access; admins never change roles or touch staff", () => {
    const o = { id: "o", role: "owner" as const };
    const a = { id: "a", role: "admin" as const };
    const a2 = { id: "a2", role: "admin" as const };
    const u = { id: "u", role: "user" as const };
    for (const act of ["plan", "access", "devices", "role"] as const) expect(canManage(o, u, act), act).toBe(true);
    for (const act of ["access", "devices", "role", "login"] as const) expect(canManage(o, a, act), act).toBe(true);
    expect(canManage(o, a, "plan")).toBe(false);
    expect(canManage(o, u, "login")).toBe(false);
    expect(canManage(a, a2, "login")).toBe(false);
    expect(canManage(o, o, "role")).toBe(false);
    expect(canManage(o, o, "access")).toBe(false);
    expect(canManage(o, o, "devices")).toBe(true);
    for (const act of ["plan", "access", "devices"] as const) expect(canManage(a, u, act), act).toBe(true);
    expect(canManage(a, u, "role")).toBe(false);
    for (const act of ["plan", "access", "devices", "role"] as const) {
      expect(canManage(a, o, act), `owner ${act}`).toBe(false);
      expect(canManage(a, a2, act), `admin ${act}`).toBe(false);
    }
    expect(canManage(a, a, "devices")).toBe(true);
    expect(canManage(a, a, "access")).toBe(false);
  });

  it("admins reach Users, Review and Developer device; Settings, AI, Devices and backups stay owner-only", async () => {
    for (const p of ["/portal", "/portal/review", "/portal/users", "/portal/pair"]) {
      expect((await get(as, p)).status, p).toBe(200);
    }
    for (const p of ["/portal/settings", "/portal/ai", "/portal/devices", "/portal/backup.json", "/portal/transfer"]) {
      expect((await get(as, p)).status, p).toBe(403);
    }
    const html = await (await get(as, "/portal/users")).text();
    expect(html).toContain('href="/portal/users"');
    expect(html).toContain("Developer device");
    expect(html).not.toContain('href="/portal/settings"');
    // No role controls for an admin.
    expect(html).not.toContain("/role");
    expect(await (await get(os, "/portal/users")).text()).toContain("/role");
  });

  it("only the owner changes roles; promotion needs a Google-linked account", async () => {
    const u = await linked("mia");
    expect((await post(as, `/portal/users/${u.id}/role`, { role: "admin" })).status).toBe(403);
    expect((await userRow(u.id)).role).toBe("user");
    expect((await post(os, `/portal/users/${u.id}/role`, { role: "admin" })).status).toBe(302);
    expect((await userRow(u.id)).role).toBe("admin");
    expect((await post(os, `/portal/users/${u.id}/role`, { role: "user" })).status).toBe(302);
    expect((await userRow(u.id)).role).toBe("user");
    const anon = await makeUser("user", "anon-1");
    expect((await post(os, `/portal/users/${anon.id}/role`, { role: "admin" })).status).toBe(400);
    expect((await userRow(anon.id)).role).toBe("user");
    expect((await post(os, `/portal/users/${u.id}/role`, { role: "owner" })).status).toBe(400);
    expect((await post(os, `/portal/users/${owner.id}/role`, { role: "user" })).status).toBe(403);
    expect((await userRow(owner.id)).role).toBe("owner");
  });

  it("admins cannot act on the owner or another admin", async () => {
    const other = await makeUser("admin", "Bea");
    for (const target of [owner.id, other.id]) {
      expect((await post(as, `/portal/users/${target}/plan`, { plan: "plus" })).status).toBe(403);
      expect((await post(as, `/portal/users/${target}/access`, { action: "disable" })).status).toBe(403);
      expect((await post(as, `/portal/users/${target}/revoke-devices`)).status).toBe(403);
    }
    expect((await userRow(owner.id)).disabled_at).toBeNull();
    expect((await userRow(other.id)).disabled_at).toBeNull();
    expect((await post(os, `/portal/users/${owner.id}/access`, { action: "disable" })).status).toBe(403);
    expect((await post(os, "/portal/users/nope/plan", { plan: "plus" })).status).toBe(404);
  });

  it("portal user actions need the session csrf", async () => {
    const u = await linked();
    const r = await post({ ...as, csrf: "wrong" }, `/portal/users/${u.id}/access`, { action: "disable" });
    expect(r.status).toBe(403);
    expect((await userRow(u.id)).disabled_at).toBeNull();
  });
});

describe("plans, access and devices", () => {
  it("sets Plus with an expiry date, which /v1/me reports; Free clears it; bad input is refused", async () => {
    const u = await linked();
    const r = await post(as, `/portal/users/${u.id}/plan`, { plan: "plus", expires: "2030-03-04", back: "/portal/users?q=g" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/users?q=g");
    const row = await userRow(u.id);
    expect(row.plan).toBe("plus");
    expect(row.plan_expires_at).toBe(Date.UTC(2030, 2, 4, 23, 59, 59, 999));
    const body = (await (await me(u)).json()) as any;
    expect(body.plan).toBe("plus");
    expect(body.planExpiresAt).toBe(row.plan_expires_at);

    expect((await post(as, `/portal/users/${u.id}/plan`, { plan: "plus", expires: "" })).status).toBe(302);
    expect((await userRow(u.id)).plan_expires_at).toBeNull();
    expect((await post(as, `/portal/users/${u.id}/plan`, { plan: "free", expires: "2030-01-01" })).status).toBe(302);
    expect(await userRow(u.id)).toMatchObject({ plan: "free", plan_expires_at: null });

    for (const bad of [{ plan: "gold" }, { plan: "plus", expires: "2001-01-01" }, { plan: "plus", expires: "2099-02-31" }, { plan: "plus", expires: "soon" }, { plan: "plus", expires: "2099-01-01" }]) {
      const res = await post(as, `/portal/users/${u.id}/plan`, bad as Record<string, string>);
      expect(res.status, JSON.stringify(bad)).toBe(400);
    }
    expect((await userRow(u.id)).plan).toBe("free");
  });

  it("disabling a user blocks their token at once; enabling restores it", async () => {
    const u = await linked();
    expect((await me(u)).status).toBe(200);
    expect((await post(as, `/portal/users/${u.id}/access`, { action: "disable" })).status).toBe(302);
    const r = await me(u);
    expect(r.status).toBe(403);
    expect(await r.json()).toEqual({ error: "account_disabled" });
    expect(await (await get(as, "/portal/users")).text()).toContain("Disabled");
    expect((await post(as, `/portal/users/${u.id}/access`, { action: "enable" })).status).toBe(302);
    expect((await me(u)).status).toBe(200);
    expect((await post(as, `/portal/users/${u.id}/access`, { action: "maybe" })).status).toBe(400);
  });

  it("disabling an admin ends their portal session", async () => {
    expect((await post(os, `/portal/users/${admin.id}/access`, { action: "disable" })).status).toBe(302);
    expect((await get(as, "/portal/users")).status).toBe(302);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM sessions WHERE user_id = ?").bind(admin.id).first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("revoke all devices signs every device out", async () => {
    const u = await linked();
    expect((await post(as, `/portal/users/${u.id}/revoke-devices`)).status).toBe(302);
    const r = await me(u);
    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "token_revoked" });
  });
});

describe("users list", () => {
  it("shows email or Anonymous, plan, role, AI used today and songs added; escapes emails", async () => {
    const g = await linked("mia", "mia@example.com");
    const evil = await linked("evil", EVIL);
    const anon = await makeUser("user", "anon-zzzz");
    await env.DB.prepare("INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, 2)")
      .bind(g.id, new Date().toISOString().slice(0, 10)).run();
    await song("Mia's song", "review", [], g.id);
    await env.DB.prepare("UPDATE users SET plan = 'plus' WHERE id = ?").bind(g.id).run();
    const html = await (await get(as, "/portal/users")).text();
    expect(html).toContain("mia@example.com");
    expect(html).toContain("Anonymous");
    expect(html).not.toContain("anon-zzzz");
    expect(html).toContain(anon.id.slice(-8));
    expect(html).toContain("Plus");
    expect(html).not.toContain("<script>alert");
    expect(html).not.toContain("<img src=x");
    expect(html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    // the row for g carries its usage and song count
    const row = html.slice(html.indexOf("mia@example.com"), html.indexOf("mia@example.com") + 3000);
    expect(row).toMatch(/data-ai="2"/);
    expect(row).toMatch(/data-songs="1"/);
    // the search box echoes the query escaped
    const qr = await get(as, `/portal/users?q=${encodeURIComponent(EVIL + EVIL)}`);
    expect(qr.status).toBe(200);
    const q = await qr.text();
    expect(q).not.toContain("<script>alert");
    expect(evil.id).toBeTruthy();
  });

  it("searches by email or id and filters by kind; paginates 50 a page", async () => {
    const g = await linked("mia", "mia@example.com");
    const now = Date.now();
    const stmts = [];
    for (let i = 0; i < 60; i++) {
      stmts.push(env.DB.prepare("INSERT INTO users (id, name, role, created_at, plan) VALUES (?, ?, 'user', ?, 'free')")
        .bind(`ANON${String(i).padStart(4, "0")}XYZ`, `anon-${i}`, now - i));
    }
    await env.DB.batch(stmts);
    const first = await (await get(as, "/portal/users?kind=anonymous")).text();
    expect(first).not.toContain("mia@example.com");
    expect((first.match(/data-user="/g) ?? []).length).toBe(50);
    expect(first).toContain("1–50 of 60");
    expect(first).toContain('href="/portal/users?kind=anonymous&amp;page=1"');
    const second = await (await get(as, "/portal/users?kind=anonymous&page=1")).text();
    expect((second.match(/data-user="/g) ?? []).length).toBe(10);
    const byEmail = await (await get(as, "/portal/users?q=MIA%40example")).text();
    expect((byEmail.match(/data-user="/g) ?? []).length).toBe(1);
    expect(byEmail).toContain(g.id.slice(-8));
    const byId = await (await get(as, "/portal/users?q=ANON0007")).text();
    expect((byId.match(/data-user="/g) ?? []).length).toBe(1);
    // LIKE wildcards in the query are literal
    expect(((await (await get(as, "/portal/users?q=%25")).text()).match(/data-user="/g) ?? []).length).toBe(0);
    const google = await (await get(as, "/portal/users?kind=google")).text();
    expect(google).toContain("mia@example.com");
    expect(google).not.toContain("ANON0001");
    const staff = await (await get(as, "/portal/users?kind=staff")).text();
    expect((staff.match(/data-user="/g) ?? []).length).toBe(2);
  });
});

describe("review tabs", () => {
  async function flag(songId: string, u: Who, reason: string, note: string | null) {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO song_flags (song_id, user_id, reason, note, created_at) VALUES (?, ?, ?, ?, ?)")
        .bind(songId, u.id, reason, note, Date.now()),
      env.DB.prepare("INSERT INTO reports (id, song_id, user_id, note, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, NULL)")
        .bind(`r-${u.id}`, songId, u.id, note ?? reason, Date.now()),
    ]);
  }

  it("Flagged lists flag counts, reasons and notes; a publish decision pins, resolves and clears it", async () => {
    const s = await song("Flagged Tune");
    await song("Quiet Tune");
    await flag(s.id, await linked("a"), "wrong_chords", "the bridge is wrong");
    await flag(s.id, await linked("b"), "wrong_chords", null);
    await flag(s.id, await linked("c"), "wrong_lyrics", EVIL);
    const html = await (await get(as, "/portal/review?tab=flagged")).text();
    expect(html).toContain("Flagged Tune");
    expect(html).not.toContain("Quiet Tune");
    expect(html).toContain("3 flags");
    expect(html).toMatch(/Wrong chords[^<]*<b>2<\/b>/);
    expect(html).toMatch(/Wrong lyrics[^<]*<b>1<\/b>/);
    expect(html).toContain("the bridge is wrong");
    expect(html).not.toContain("<script>alert");
    expect(html).toMatch(/Flagged\s*<span[^>]*>1<\/span>/);

    const r = await post(as, `/portal/songs/${s.id}/status`, { status: "published", back: "/portal/review?tab=flagged" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/review?tab=flagged");
    const row = await env.DB.prepare("SELECT status, moderated_by FROM songs WHERE id = ?").bind(s.id).first<any>();
    expect(row).toEqual({ status: "published", moderated_by: admin.id });
    const open = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports WHERE song_id = ? AND resolved_at IS NULL").bind(s.id).first<{ n: number }>();
    expect(open!.n).toBe(0);
    expect(await (await get(as, "/portal/review?tab=flagged")).text()).not.toContain("Flagged Tune");
  });

  it("Hidden lists hidden songs; Delete removes a hidden or review song but never a published one", async () => {
    const h = await song("Hidden Tune", "hidden");
    const rv = await song("Review Tune", "review");
    const p = await song("Public Tune", "published");
    const html = await (await get(as, "/portal/review?tab=hidden")).text();
    expect(html).toContain("Hidden Tune");
    expect(html).not.toContain("Review Tune");
    expect(html).toContain(`action="/portal/songs/${h.id}/delete"`);
    expect(await (await get(as, "/portal/review?tab=review")).text()).toContain("Review Tune");
    for (const id of [h.id, rv.id]) {
      expect((await post(as, `/portal/songs/${id}/delete`, { back: "/portal/review?tab=hidden" })).status).toBe(302);
      expect(await env.DB.prepare("SELECT 1 AS x FROM songs WHERE id = ?").bind(id).first()).toBeNull();
      expect(await env.DB.prepare("SELECT 1 AS x FROM songs_fts WHERE song_id = ?").bind(id).first()).toBeNull();
    }
    expect((await post(as, `/portal/songs/${p.id}/delete`)).status).toBe(409);
    expect(await env.DB.prepare("SELECT 1 AS x FROM songs WHERE id = ?").bind(p.id).first()).not.toBeNull();
  });
});

describe("settings", () => {
  it("validates Google client IDs: each line must end .apps.googleusercontent.com", async () => {
    const base = { anon_ai_daily: "1", anon_ai_daily_per_ip: "10", free_ai_daily: "3", plus_ai_daily: "50", flag_hide_threshold: "3", ...SEARCH, ...VOICE };
    const bad = await post(os, "/portal/settings", { ...base, google_client_ids: `${GOOD_ID}\nevil.example.com` });
    expect(bad.status).toBe(400);
    expect(await bad.text()).toContain("evil.example.com");
    let v = await env.DB.prepare("SELECT value FROM settings WHERE key = 'google_client_ids'").first<{ value: string }>();
    expect(v!.value).toBe("[]");
    const spoof = await post(os, "/portal/settings", { ...base, google_client_ids: "x.apps.googleusercontent.com.evil.com" });
    expect(spoof.status).toBe(400);
    const ok = await post(os, "/portal/settings", { ...base, google_client_ids: `  ${GOOD_ID}  \r\n\n${GOOD_ID_2}\n${GOOD_ID}\n` });
    expect(ok.status).toBe(302);
    v = await env.DB.prepare("SELECT value FROM settings WHERE key = 'google_client_ids'").first<{ value: string }>();
    expect(JSON.parse(v!.value)).toEqual([GOOD_ID, GOOD_ID_2]);
    const page = await (await get(os, "/portal/settings")).text();
    expect(page).toContain(GOOD_ID_2);
    expect(page).not.toContain("discover_daily_limit");
  });

  it("stores plan limits and the flag threshold, refusing out-of-range numbers", async () => {
    const fields = {
      anon_ai_daily: "2", anon_ai_daily_per_ip: "25", free_ai_daily: "4", plus_ai_daily: "80", flag_hide_threshold: "5", google_client_ids: "",
      search_daily_anon: "3", search_daily_free: "11", search_daily_plus: "60", search_daily_per_ip: "40", search_daily_global: "900",
      ...VOICE, voice_daily_free: "12", stt_order: "inworld,workers-ai", inworld_stt_model: "groq/whisper-large-v3",
    };
    expect((await post(os, "/portal/settings", fields)).status).toBe(302);
    const { results } = await env.DB.prepare(
      "SELECT key, value FROM settings WHERE key LIKE 'search_daily_%' OR key LIKE 'voice_daily_%' OR key IN ('stt_order','inworld_stt_model','anon_ai_daily','anon_ai_daily_per_ip','free_ai_daily','plus_ai_daily','flag_hide_threshold')",
    ).all<any>();
    expect(Object.fromEntries(results.map((r) => [r.key, r.value]))).toEqual(Object.fromEntries(Object.entries(fields).filter(([k]) => k !== "google_client_ids")));
    // the page shows the stored values
    expect(await (await get(os, "/portal/settings")).text()).toContain('name="search_daily_global" min="0" max="100000" step="1" required="" value="900"');
    expect(await (await get(os, "/portal/settings")).text()).toContain('name="stt_order"');
    for (const bad of [{ stt_order: "workers-ai,openai" }, { stt_order: "inworld;x" }, { inworld_stt_model: "bad model!" }, { voice_daily_global: "100001" }, { free_ai_daily: "-1" }, { flag_hide_threshold: "0" }, { plus_ai_daily: "abc" }, { anon_ai_daily_per_ip: "" }, { search_daily_global: "100001" }]) {
      expect((await post(os, "/portal/settings", { ...fields, ...bad })).status, JSON.stringify(bad)).toBe(400);
    }
  });

  it("migration 0012 seeds anon_ai_daily_per_ip = 10", async () => {
    const r = await env.DB.prepare("SELECT value FROM settings WHERE key = 'anon_ai_daily_per_ip'").first<{ value: string }>();
    expect(r!.value).toBe("10");
  });

  it("private songs: only staff-added ones; hide touches published, publish touches hidden; repeats change nothing", async () => {
    const member = await linked("m");
    const a = await song("Private One", "published", ["private"], owner.id);
    const b = await song("Private Two", "hidden", ["Bollywood", "Private"], admin.id);
    const c = await song("Member Review", "review", ["private"], member.id);
    const m = await song("Member Public", "published", ["private"], member.id);
    const d = await song("Public Song", "published", ["hymn"], owner.id);
    const page = await (await get(os, "/portal/settings")).text();
    expect(page).toMatch(/data-private-count="2"/);
    expect((await post(as, "/portal/settings/private", { action: "hide" })).status).toBe(403);
    const rows = async () => Object.fromEntries(
      (await env.DB.prepare("SELECT id, status, version, moderated_at, moderated_by FROM songs").all<any>()).results.map((r) => [r.id, r]),
    );
    const before = await rows();
    expect((await post(os, "/portal/settings/private", { action: "hide" })).status).toBe(302);
    let all = await rows();
    expect(all[a.id]).toMatchObject({ status: "hidden", moderated_by: owner.id, version: before[a.id].version + 1 });
    expect(all[b.id]).toEqual(before[b.id]); // already hidden: untouched, no pin
    for (const x of [c, m, d]) expect(all[x.id], x.title).toEqual(before[x.id]);
    // a second press changes nothing at all (pin timestamps included)
    const afterHide = all;
    expect((await post(os, "/portal/settings/private", { action: "hide" })).status).toBe(302);
    expect(await rows()).toEqual(afterHide);

    expect((await post(os, "/portal/settings/private", { action: "publish" })).status).toBe(302);
    all = await rows();
    for (const x of [a, b]) expect(all[x.id], x.title).toMatchObject({ status: "published", moderated_by: owner.id });
    for (const x of [c, m, d]) expect(all[x.id], x.title).toEqual(before[x.id]);
    const afterPublish = all;
    expect((await post(os, "/portal/settings/private", { action: "publish" })).status).toBe(302);
    expect(await rows()).toEqual(afterPublish);
    expect((await post(os, "/portal/settings/private", { action: "delete" })).status).toBe(400);
  });
});

describe("portal login for promoted admins", () => {
  async function preLogin(path: string) {
    const pre = await SELF.fetch(ORIGIN + path, { redirect: "manual" });
    const cookie = pre.headers.getSetCookie().find((c) => c.startsWith("ot_csrf="))?.split(";")[0] ?? "";
    const html = await pre.text();
    return { status: pre.status, html, cookie, csrf: html.match(/name="_csrf" value="([^"]+)"/)?.[1] ?? "" };
  }
  async function makeLink(target: Who) {
    const r = await post(os, `/portal/users/${target.id}/portal-login`);
    expect(r.status).toBe(200);
    const html = await r.text();
    const token = html.match(/\/portal\/admin-setup\?token=([A-Za-z0-9_-]+)/)![1];
    return token;
  }
  const setup = (pre: { cookie: string; csrf: string }, fields: Record<string, string>, ip = "203.0.113.5") =>
    SELF.fetch(`${ORIGIN}/portal/admin-setup`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: pre.cookie, "cf-connecting-ip": ip },
      body: new URLSearchParams({ _csrf: pre.csrf, ...fields }),
      redirect: "manual",
    });

  it("only the owner creates a link, and only for an admin", async () => {
    const other = await makeUser("admin", "Bea");
    expect((await post(as, `/portal/users/${other.id}/portal-login`)).status).toBe(403);
    const u = await linked("plain");
    expect((await post(os, `/portal/users/${u.id}/portal-login`)).status).toBe(400);
    expect((await post(os, `/portal/users/${owner.id}/portal-login`)).status).toBe(403);
    const html = await (await get(os, "/portal/users?kind=staff")).text();
    expect(html).toContain(`/portal/users/${admin.id}/portal-login`);
    expect(await (await get(as, "/portal/users?kind=staff")).text()).not.toContain("/portal-login");
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM admin_setup_tokens").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("the link sets a password once; the admin can then sign in; reuse fails", async () => {
    const token = await makeLink(admin);
    const stored = await env.DB.prepare("SELECT token_hash, expires_at FROM admin_setup_tokens").first<any>();
    expect(stored.token_hash).toBe(await sha256Hex(token));
    expect(stored.expires_at).toBeGreaterThan(Date.now() + 23 * 3_600_000);
    const pre = await preLogin(`/portal/admin-setup?token=${token}`);
    expect(pre.status).toBe(200);
    expect(pre.html).toContain("Ada");
    expect((await setup(pre, { token, name: "Ada Portal", password: "short" })).status).toBe(400);
    const r = await setup(pre, { token, name: "Ada Portal", password: "a long admin password" });
    expect(r.status).toBe(302);
    expect(r.headers.getSetCookie().some((c) => c.startsWith("ot_session="))).toBe(true);
    const row = await env.DB.prepare("SELECT name, password_hash FROM users WHERE id = ?").bind(admin.id).first<any>();
    expect(row.name).toBe("Ada Portal");
    expect(row.password_hash).toBeTruthy();
    // the normal login now works
    const login = await preLogin("/portal/login");
    const li = await SELF.fetch(`${ORIGIN}/portal/login`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: login.cookie },
      body: new URLSearchParams({ _csrf: login.csrf, name: "Ada Portal", password: "a long admin password" }),
      redirect: "manual",
    });
    expect(li.status).toBe(302);
    // single use
    expect((await preLogin(`/portal/admin-setup?token=${token}`)).status).toBe(404);
    expect((await setup(pre, { token, name: "Ada Again", password: "another long password" })).status).toBe(404);
  });

  it("expired links, demoted accounts and taken names are refused", async () => {
    let token = await makeLink(admin);
    await env.DB.prepare("UPDATE admin_setup_tokens SET expires_at = ?").bind(Date.now() - 1).run();
    expect((await preLogin(`/portal/admin-setup?token=${token}`)).status).toBe(404);
    token = await makeLink(admin);
    const pre = await preLogin(`/portal/admin-setup?token=${token}`);
    expect((await setup(pre, { token, name: "owner", password: "a long admin password" })).status).toBe(409);
    await env.DB.prepare("UPDATE users SET role = 'user' WHERE id = ?").bind(admin.id).run();
    expect((await setup(pre, { token, name: "Ada", password: "a long admin password" })).status).toBe(404);
    const row = await env.DB.prepare("SELECT password_hash FROM users WHERE id = ?").bind(admin.id).first<any>();
    expect(row.password_hash).toBeNull();
  });

  it("guessing tokens is rate limited per IP like login", async () => {
    const pre = await preLogin("/portal/login");
    const form = await preLogin("/portal/admin-setup?token=nope");
    expect(form.status).toBe(404);
    let last = 0;
    for (let i = 0; i < 6; i++) {
      last = (await setup(pre, { token: `guess-${i}-xxxxxxxxxxxxxxxxxxxxxxxx`, name: "X", password: "a long admin password" }, "192.0.2.77")).status;
    }
    expect(last).toBe(429);
  });
});

describe("S6 follow-ups", () => {
  async function linkFor(u: Who) {
    const r = await post(os, `/portal/users/${u.id}/portal-login`);
    expect(r.status).toBe(200);
    return (await r.text()).match(/\/portal\/admin-setup\?token=([A-Za-z0-9_-]+)/)![1];
  }

  it("a second redemption in the same millisecond changes nothing (claim nonce)", async () => {
    const token = await linkFor(admin);
    const tokenHash = await sha256Hex(token);
    const now = Date.now();
    const first = await redeemAdminSetup(env.DB, { tokenHash, userId: admin.id, name: "Ada One", passwordHash: await hashPassword("first password 123"), now });
    expect(first).toBe("ok");
    const second = await redeemAdminSetup(env.DB, { tokenHash, userId: admin.id, name: "Ada Two", passwordHash: await hashPassword("second password 456"), now });
    expect(second).toBe("invalid");
    const row = await env.DB.prepare("SELECT name, password_hash FROM users WHERE id = ?").bind(admin.id).first<any>();
    expect(row.name).toBe("Ada One");
    expect(await verifyPassword("first password 123", row.password_hash)).toBe(true);
  });

  it("a name taken by other staff refuses the redemption without burning the token", async () => {
    const token = await linkFor(admin);
    const tokenHash = await sha256Hex(token);
    const r = await redeemAdminSetup(env.DB, { tokenHash, userId: admin.id, name: "OWNER", passwordHash: await hashPassword("x password 123"), now: Date.now() });
    expect(r).toBe("name_taken");
    const t = await env.DB.prepare("SELECT used_at FROM admin_setup_tokens WHERE token_hash = ?").bind(tokenHash).first<any>();
    expect(t.used_at).toBeNull();
    const row = await env.DB.prepare("SELECT password_hash FROM users WHERE id = ?").bind(admin.id).first<any>();
    expect(row.password_hash).toBeNull();
  });

  it("changing a role deletes the account's unused setup links", async () => {
    await linkFor(admin);
    expect((await post(os, `/portal/users/${admin.id}/role`, { role: "user" })).status).toBe(302);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM admin_setup_tokens WHERE user_id = ?").bind(admin.id).first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("redeeming a link ends the admin's other portal sessions", async () => {
    const token = await linkFor(admin);
    const pre = await SELF.fetch(`${ORIGIN}/portal/admin-setup?token=${token}`);
    const cookie = pre.headers.getSetCookie().find((c) => c.startsWith("ot_csrf="))!.split(";")[0];
    const csrf = (await pre.text()).match(/name="_csrf" value="([^"]+)"/)![1];
    const r = await SELF.fetch(`${ORIGIN}/portal/admin-setup`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie, "cf-connecting-ip": "203.0.113.90" },
      body: new URLSearchParams({ _csrf: csrf, token, name: "Ada", password: "a long admin password" }),
      redirect: "manual",
    });
    expect(r.status).toBe(302);
    // the session made in beforeEach is gone; exactly the new one remains
    expect((await get(as, "/portal/users")).status).toBe(302);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM sessions WHERE user_id = ?").bind(admin.id).first<{ n: number }>();
    expect(n!.n).toBe(1);
  });

  it("deleting a song bumps modified_at of the playlists that held it", async () => {
    const h = await song("Gone Tune", "hidden");
    const u = await linked();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at, modified_at) VALUES ('pl1', ?, 'Set', 0, 5, 5)").bind(u.id),
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at, modified_at) VALUES ('pl2', ?, 'Other', 0, 5, 5)").bind(u.id),
      env.DB.prepare("INSERT INTO playlist_items (playlist_id, song_id, position) VALUES ('pl1', ?, 'a')").bind(h.id),
    ]);
    const before = Date.now();
    expect((await post(as, `/portal/songs/${h.id}/delete`)).status).toBe(302);
    const rows = (await env.DB.prepare("SELECT id, modified_at FROM playlists ORDER BY id").all<any>()).results;
    expect(rows[0].modified_at).toBeGreaterThanOrEqual(before);
    expect(rows[1].modified_at).toBe(5);
  });
});

describe("minor admin fixes", () => {
  it("deleting a song also removes it from favourites and playlists", async () => {
    const h = await song("Gone Tune", "hidden");
    const u = await linked();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO favourites (user_id, song_id, favourite, updated_at) VALUES (?, ?, 1, 1)").bind(u.id, h.id),
      env.DB.prepare("INSERT INTO playlist_items (playlist_id, song_id, position) VALUES ('p1', ?, 'a')").bind(h.id),
    ]);
    expect((await post(as, `/portal/songs/${h.id}/delete`)).status).toBe(302);
    expect(await env.DB.prepare("SELECT 1 AS x FROM favourites WHERE song_id = ?").bind(h.id).first()).toBeNull();
    expect(await env.DB.prepare("SELECT 1 AS x FROM playlist_items WHERE song_id = ?").bind(h.id).first()).toBeNull();
  });

  it("staff rows have no plan panel", async () => {
    const html = await (await get(os, "/portal/users?kind=staff")).text();
    expect(html).not.toContain(`/portal/users/${admin.id}/plan`);
  });
});

describe("AI & keys: search actor", () => {
  it("shows and saves the Find on the web search actor; malformed actors are refused", async () => {
    expect(await (await get(os, "/portal/ai")).text()).toContain('value="apify~google-search-scraper"');
    expect((await post(os, "/portal/ai/apify", { apify_actor: "apify~rag-web-browser", apify_search_actor: "someone~search" })).status).toBe(302);
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_search_actor'").first<{ value: string }>();
    expect(row!.value).toBe("someone~search");
    expect((await post(os, "/portal/ai/apify", { apify_actor: "apify~rag-web-browser", apify_search_actor: "https://evil" })).status).toBe(400);
    expect((await env.DB.prepare("SELECT value FROM settings WHERE key = 'apify_search_actor'").first<{ value: string }>())!.value).toBe("someone~search");
  });
});

describe("library dashboard and developer device", () => {
  it("the library shows total songs, songs added today, users today and AI used vs the global limit", async () => {
    await song("One");
    await song("Two");
    const day = new Date().toISOString().slice(0, 10);
    await env.DB.batch([
      env.DB.prepare("UPDATE settings SET value = '100' WHERE key = 'ai_daily_limit'"),
      env.DB.prepare("INSERT OR REPLACE INTO ai_usage (day, count) VALUES (?, 7)").bind(day),
      env.DB.prepare("UPDATE users SET last_seen_at = ?").bind(Date.now()),
    ]);
    const html = await (await get(as, "/portal")).text();
    expect(html).toMatch(/data-stat="songs"[^>]*>[\s\S]*?<b>2<\/b>/);
    expect(html).toMatch(/data-stat="added"[^>]*>[\s\S]*?<b>2<\/b>/);
    expect(html).toMatch(/data-stat="users"[^>]*>[\s\S]*?<b>2<\/b>/);
    expect(html).toMatch(/data-stat="ai"[^>]*>[\s\S]*?<b>7<\/b>[\s\S]*?of 100/);
  });

  it("Developer device pairs a code to the signed-in admin's own account", async () => {
    const u = await linked();
    const r = await post(as, "/portal/pair", { user_id: u.id });
    expect(r.status).toBe(200);
    const row = await env.DB.prepare("SELECT user_id FROM pairing_codes").first<{ user_id: string }>();
    expect(row!.user_id).toBe(admin.id);
  });
});

describe("AI limits for anonymous users per IP, and staff rate exemption", () => {
  const e = () => env as unknown as Env;
  const as429 = async (p: Promise<unknown>) => {
    try {
      await p;
    } catch (err) {
      return err instanceof ApiError ? { status: err.status, code: err.code } : err;
    }
    return null;
  };

  it("anonymous accounts on one IP share anon_ai_daily_per_ip; other IPs and Google users are unaffected", async () => {
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('anon_ai_daily_per_ip', '2')").run();
    const ip = "198.51.100.7";
    const anons = [await makeUser("user", "a1"), await makeUser("user", "a2"), await makeUser("user", "a3")];
    const who = (u: Who, ipKey = ip) => ({ id: u.id, role: "user" as const, ipKey });
    const r1 = await reserveAi(e(), who(anons[0]), 1);
    await reserveAi(e(), who(anons[1]), 1);
    expect(await as429(reserveAi(e(), who(anons[2]), 1))).toEqual({ status: 429, code: "plan_limit_reached" });
    // the refused reservation took nothing from the user's own allowance
    const used = await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(anons[2].id).first<{ count: number }>();
    expect(used?.count ?? 0).toBe(0);
    // another IP is fine
    expect(await as429(reserveAi(e(), who(anons[2], "203.0.113.9"), 1))).toBeNull();
    // a Google-linked free user on the same IP is not counted against it
    const g = await linked();
    expect(await as429(reserveAi(e(), { id: g.id, role: "user", ipKey: ip }, 1))).toBeNull();
    // a refund gives the IP unit back
    await refundAi(e(), r1, 1);
    const ipRow = await env.DB.prepare("SELECT count FROM ip_ai_usage WHERE ip_key = ?").bind(ip).first<{ count: number }>();
    expect(ipRow!.count).toBe(1);
  });

  it("partial reservations (discover) grant min(user remaining, IP remaining) for anonymous users", async () => {
    await env.DB.batch([
      env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('anon_ai_daily_per_ip', '3')"),
      env.DB.prepare("UPDATE settings SET value = '5' WHERE key = 'anon_ai_daily'"),
    ]);
    const ip = "192.0.2.44";
    const a = await makeUser("user", "p1");
    const b = await makeUser("user", "p2");
    const who = (u: Who) => ({ id: u.id, role: "user" as const, ipKey: ip });
    expect((await aiRemaining(e(), who(a))).remaining).toBe(3);
    const ra = await reserveAiUpTo(e(), who(a), 2);
    expect(ra.granted).toBe(2);
    const rb = await reserveAiUpTo(e(), who(b), 3);
    expect(rb.granted).toBe(1);
    const usedB = await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(b.id).first<{ count: number }>();
    expect(usedB!.count).toBe(1);
    expect(await as429(reserveAiUpTo(e(), who(b), 1))).toEqual({ status: 429, code: "plan_limit_reached" });
    expect((await env.DB.prepare("SELECT count FROM user_ai_usage WHERE user_id = ?").bind(b.id).first<{ count: number }>())!.count).toBe(1);
    expect((await aiRemaining(e(), who(b))).remaining).toBe(0);
    await refundAi(e(), ra.reservation, 2);
    expect((await aiRemaining(e(), who(b))).remaining).toBe(2);
  });

  it("the device middleware carries the client IP into the reservation", async () => {
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('anon_ai_daily_per_ip', '1')").run();
    const sent: unknown[] = [];
    const testEnv = { ...(env as unknown as Env), IMPORT_QUEUE: { sendBatch: async (m: Iterable<unknown>) => { for (const x of m) sent.push(x); } } } as unknown as Env;
    const ctx = { waitUntil() {}, passThroughOnException() {}, props: {} } as unknown as ExecutionContext;
    const call = (u: Who, ip: string) =>
      worker.fetch!(new Request(`${ORIGIN}/v1/import/links`, {
        method: "POST",
        headers: { ...u.headers, "content-type": "application/json", "cf-connecting-ip": ip },
        body: JSON.stringify({ urls: ["https://example.com/a"] }),
      }) as any, testEnv, ctx);
    const a = await makeUser("user", "x1");
    const b = await makeUser("user", "x2");
    expect((await call(a, "2001:db8:1:2::1")).status).toBe(202);
    const r = await call(b, "2001:db8:1:2::99"); // same /64
    expect(r.status).toBe(429);
    expect(((await r.json()) as any).error).toBe("plan_limit_reached");
  });

  it("owner and admin are exempt from the per-minute AI rate limit", async () => {
    for (let i = 0; i < AI_REQUESTS_PER_MINUTE + 5; i++) {
      await checkAiRate(e(), { id: admin.id, role: "admin" });
      await checkAiRate(e(), { id: owner.id, role: "owner" });
    }
    const u = await linked();
    for (let i = 0; i < AI_REQUESTS_PER_MINUTE; i++) await checkAiRate(e(), { id: u.id, role: "user" });
    expect(await as429(checkAiRate(e(), { id: u.id, role: "user" }))).toEqual({ status: 429, code: "rate_limited" });
  });
});
