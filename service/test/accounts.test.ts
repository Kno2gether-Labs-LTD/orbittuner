import { applyD1Migrations, env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { hashPassword } from "../src/lib/crypto";
import { upsertSong } from "../src/songs/repo";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

function anon(ip: string, body: unknown = { deviceName: "Pixel 9" }) {
  return SELF.fetch(ORIGIN + "/v1/accounts/anonymous", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

const call = (method: string, path: string, token: string) =>
  SELF.fetch(ORIGIN + path, { method, headers: { authorization: `Bearer ${token}` } });

beforeEach(async () => {
  await clearSongs();
});

describe("POST /v1/accounts/anonymous", () => {
  it("creates a user account with a device token that can call /v1/me", async () => {
    const r = await anon("203.0.113.7");
    expect(r.status).toBe(201);
    expect(r.headers.get("cache-control")).toBe("no-store");
    const body = await r.json<any>();
    expect(body.deviceToken).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    expect(body.user).toEqual({ id: expect.any(String), role: "user", plan: "free" });

    const row = await env.DB.prepare("SELECT role, plan, password_hash FROM users WHERE id = ?").bind(body.user.id).first<any>();
    expect(row).toEqual({ role: "user", plan: "free", password_hash: null });
    const dev = await env.DB.prepare("SELECT name FROM devices WHERE user_id = ?").bind(body.user.id).first<any>();
    expect(dev.name).toBe("Pixel 9");

    const me = await call("GET", "/v1/me", body.deviceToken);
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({
      id: body.user.id, role: "user", plan: "free", planExpiresAt: null, google: null,
      aiLeftToday: 1, aiDailyLimit: 1, // anonymous tier (anon_ai_daily)
    });
  });

  it("does not need a device token and ignores a missing deviceName", async () => {
    const r = await anon("203.0.113.8", {});
    expect(r.status).toBe(201);
    const { user } = await r.json<any>();
    const dev = await env.DB.prepare("SELECT name FROM devices WHERE user_id = ?").bind(user.id).first<any>();
    expect(dev.name).toBe("Android device");
  });

  it("rejects a body over 4 KB with 413 and malformed JSON with 400", async () => {
    expect((await anon("203.0.113.9", { deviceName: "x".repeat(5000) })).status).toBe(413);
    expect((await anon("203.0.113.9", "not json")).status).toBe(400);
  });

  it("the 6th account from one IP within an hour is rate limited", async () => {
    for (let i = 0; i < 5; i++) expect((await anon("198.51.100.20")).status).toBe(201);
    const r = await anon("198.51.100.20");
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
    expect((await anon("198.51.100.21")).status).toBe(201);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE role = 'user'").first<{ n: number }>();
    expect(n!.n).toBe(6);
  });

  it("rotating addresses inside one IPv6 /64 shares a limit; another /64 is fine", async () => {
    for (let i = 1; i <= 5; i++) expect((await anon(`2001:db8:1:2::${i}`)).status).toBe(201);
    expect((await anon("2001:db8:1:2:aaaa::ffff")).status).toBe(429);
    expect((await anon("2001:db8:1:3::1")).status).toBe(201);
  });

  it("a parallel burst from one IP creates at most 5 accounts", async () => {
    const res = await Promise.all(Array.from({ length: 8 }, () => anon("192.0.2.50")));
    expect(res.filter((r) => r.status === 201).length).toBe(5);
    expect(res.filter((r) => r.status === 429).length).toBe(3);
  });

  it("attempts older than an hour no longer count", async () => {
    const old = Date.now() - 61 * 60_000;
    const stmts = Array.from({ length: 5 }, () =>
      env.DB.prepare("INSERT INTO account_attempts (ip_key, at) VALUES ('192.0.2.60', ?)").bind(old),
    );
    await env.DB.batch(stmts);
    expect((await anon("192.0.2.60")).status).toBe(201);
  });
});

describe("GET /v1/me", () => {
  it("reports plan, expiry, Google email and owner limits", async () => {
    const u = await makeUser("user", "u");
    await env.DB.prepare("UPDATE users SET plan = 'plus', plan_expires_at = 4102444800000, google_sub = 'g1', email = 'u@example.com' WHERE id = ?")
      .bind(u.id).run();
    const me = await (await call("GET", "/v1/me", u.token)).json<any>();
    expect(me).toMatchObject({ role: "user", plan: "plus", planExpiresAt: 4102444800000, google: { email: "u@example.com" }, aiDailyLimit: 50 });

    const o = await makeUser("owner", "o");
    const om = await (await call("GET", "/v1/me", o.token)).json<any>();
    expect(om).toMatchObject({ role: "owner", plan: "free", google: null, aiDailyLimit: 100 });
  });

  it("an expired Plus plan counts as Free for the daily limit", async () => {
    const u = await makeUser("user", "u");
    await env.DB.prepare("UPDATE users SET plan = 'plus', plan_expires_at = 1000, google_sub = 'g-exp' WHERE id = ?").bind(u.id).run();
    const me = await (await call("GET", "/v1/me", u.token)).json<any>();
    expect(me.aiDailyLimit).toBe(3);
  });
});

describe("DELETE /v1/devices/me", () => {
  it("revokes this device's token", async () => {
    const created = await (await anon("203.0.113.30")).json<any>();
    const r = await call("DELETE", "/v1/devices/me", created.deviceToken);
    expect(r.status).toBe(204);
    const again = await call("GET", "/v1/me", created.deviceToken);
    expect(again.status).toBe(401);
    expect(await again.json()).toEqual({ error: "token_revoked" });
  });

  it("leaves the user's other devices working", async () => {
    const u = await makeUser("user", "u");
    const created = await (await anon("203.0.113.31")).json<any>();
    expect((await call("DELETE", "/v1/devices/me", created.deviceToken)).status).toBe(204);
    expect((await call("GET", "/v1/me", u.token)).status).toBe(200);
  });
});

describe("disabled accounts", () => {
  it("get 403 account_disabled on every /v1 route", async () => {
    const u = await makeUser("user", "u");
    await env.DB.prepare("UPDATE users SET disabled_at = ? WHERE id = ?").bind(Date.now(), u.id).run();
    for (const [m, p] of [["GET", "/v1/me"], ["GET", "/v1/browse"], ["GET", "/v1/me/favourites"], ["DELETE", "/v1/devices/me"], ["GET", "/v1/nope"]]) {
      const r = await call(m, p, u.token);
      expect(r.status, `${m} ${p}`).toBe(403);
      expect(await r.json()).toEqual({ error: "account_disabled" });
    }
  });
});

describe("roles and visibility", () => {
  it("owner and admin see review and hidden songs; a user sees published plus their own", async () => {
    const owner = await makeUser("owner", "o");
    const admin = await makeUser("admin", "a");
    const user = await makeUser("user", "u");
    const other = await makeUser("user", "x");
    const own = await upsertSong(env.DB, { chordpro: "{title: Mine}\n[C]la la\n", source: "web", status: "review", createdBy: user.id });
    const theirs = await upsertSong(env.DB, { chordpro: "{title: Theirs}\n[C]la la\n", source: "web", status: "review", createdBy: other.id });
    const hidden = await upsertSong(env.DB, { chordpro: "{title: Gone}\n[C]la la\n", source: "web", status: "hidden", createdBy: other.id });
    const pub = await upsertSong(env.DB, { chordpro: "{title: Open}\n[C]la la\n", source: "owner", status: "published", createdBy: owner.id });

    for (const who of [owner, admin]) {
      for (const s of [own, theirs, hidden, pub]) expect((await call("GET", `/v1/songs/${s.id}`, who.token)).status).toBe(200);
    }
    expect((await call("GET", `/v1/songs/${own.id}`, user.token)).status).toBe(200);
    expect((await call("GET", `/v1/songs/${pub.id}`, user.token)).status).toBe(200);
    expect((await call("GET", `/v1/songs/${theirs.id}`, user.token)).status).toBe(404);
    expect((await call("GET", `/v1/songs/${hidden.id}`, user.token)).status).toBe(404);
  });

  it("owner-only API actions stay owner-only for admins", async () => {
    const admin = await makeUser("admin", "a");
    expect((await call("GET", "/v1/export/json", admin.token)).status).toBe(403);
    const owner = await makeUser("owner", "o");
    expect((await call("GET", "/v1/export/json", owner.token)).status).toBe(200);
  });
});

describe("portal login roles", () => {
  async function loginAs(name: string, password: string) {
    const pre = await SELF.fetch(`${ORIGIN}/portal/login`);
    const csrfCookie = pre.headers.getSetCookie().find((c) => c.startsWith("ot_csrf="))!.split(";")[0];
    const csrf = (await pre.text()).match(/name="_csrf" value="([^"]+)"/)![1];
    return SELF.fetch(`${ORIGIN}/portal/login`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: csrfCookie },
      body: new URLSearchParams({ name, password, _csrf: csrf }),
      redirect: "manual",
    });
  }

  it("an admin can log in and moderate, but owner-only pages are forbidden", async () => {
    await env.DB.prepare("INSERT INTO users (id, name, role, password_hash, created_at) VALUES ('A1','Ada','admin',?,1)")
      .bind(await hashPassword("admin-password")).run();
    const r = await loginAs("Ada", "admin-password");
    expect(r.status).toBe(302);
    const sess = r.headers.getSetCookie().find((c) => c.startsWith("ot_session="))!.split(";")[0];
    const home = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie: sess }, redirect: "manual" });
    expect(home.status).toBe(200);
    const html = await home.text();
    expect(html).toContain("Admin");
    expect(html).not.toContain('href="/portal/ai"');
    expect((await SELF.fetch(`${ORIGIN}/portal/review`, { headers: { cookie: sess }, redirect: "manual" })).status).toBe(200);
    expect((await SELF.fetch(`${ORIGIN}/portal/users`, { headers: { cookie: sess }, redirect: "manual" })).status).toBe(200);
    for (const p of ["/portal/ai", "/portal/settings", "/portal/backup.json"]) {
      expect((await SELF.fetch(ORIGIN + p, { headers: { cookie: sess }, redirect: "manual" })).status, p).toBe(403);
    }
  });

  it("a user cannot log into the portal", async () => {
    await env.DB.prepare("INSERT INTO users (id, name, role, password_hash, created_at) VALUES ('U1','Una','user',?,1)")
      .bind(await hashPassword("user-password")).run();
    expect((await loginAs("Una", "user-password")).status).toBe(401);
  });

  it("a disabled admin cannot log in", async () => {
    await env.DB.prepare("INSERT INTO users (id, name, role, password_hash, created_at, disabled_at) VALUES ('A2','Dis','admin',?,1,5)")
      .bind(await hashPassword("admin-password")).run();
    expect((await loginAs("Dis", "admin-password")).status).toBe(401);
  });
});

describe("migration 0007", () => {
  it("rebuilds users keeping every row and FK-dependent table, mapping member to user", async () => {
    const db = env.MIGRATION_DB;
    const all = env.TEST_MIGRATIONS;
    const idx = all.findIndex((m) => m.name.startsWith("0007"));
    expect(idx).toBe(6);
    await applyD1Migrations(db, all.slice(0, idx));
    await db.batch([
      db.prepare("INSERT INTO users (id, name, role, password_hash, created_at) VALUES ('O', 'Owner', 'owner', 'pbkdf2$x', 1)"),
      db.prepare("INSERT INTO users (id, name, role, password_hash, created_at) VALUES ('M', 'Mia', 'member', NULL, 2)"),
      db.prepare("INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES ('D1', 'O', 'phone', 'h1', NULL, 3, 3)"),
      db.prepare("INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES ('D2', 'M', 'tab', 'h2', 9, 4, 4)"),
      db.prepare("INSERT INTO sessions (id_hash, user_id, csrf, expires_at) VALUES ('S', 'O', 'c', 99)"),
      db.prepare("INSERT INTO songs (id, title, chordpro, source, status, created_by, created_at, updated_at) VALUES ('X', 'Song', '{title: Song}', 'owner', 'published', 'O', 5, 5)"),
      db.prepare("INSERT INTO favourites (user_id, song_id, favourite, updated_at) VALUES ('M', 'X', 1, 6)"),
      db.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at) VALUES ('P', 'O', 'Sunday', 0, 7)"),
      db.prepare("INSERT INTO playlist_items (playlist_id, song_id, position, settings) VALUES ('P', 'X', 'a', '{}')"),
    ]);

    await applyD1Migrations(db, all);

    const users = await db.prepare("SELECT id, name, role, password_hash, created_at, plan, plan_expires_at, google_sub, email, disabled_at, last_seen_at FROM users ORDER BY id").all();
    expect(users.results).toEqual([
      { id: "M", name: "Mia", role: "user", password_hash: null, created_at: 2, plan: "free", plan_expires_at: null, google_sub: null, email: null, disabled_at: null, last_seen_at: null },
      { id: "O", name: "Owner", role: "owner", password_hash: "pbkdf2$x", created_at: 1, plan: "free", plan_expires_at: null, google_sub: null, email: null, disabled_at: null, last_seen_at: null },
    ]);
    expect((await db.prepare("SELECT id, user_id, revoked_at FROM devices ORDER BY id").all()).results).toEqual([
      { id: "D1", user_id: "O", revoked_at: null },
      { id: "D2", user_id: "M", revoked_at: 9 },
    ]);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM sessions").first<any>()).n).toBe(1);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM songs").first<any>()).n).toBe(1);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM favourites").first<any>()).n).toBe(1);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM playlists").first<any>()).n).toBe(1);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM playlist_items").first<any>()).n).toBe(1);

    // The devices FK still points at the rebuilt users table.
    const fk = await db.prepare("SELECT \"table\" AS t FROM pragma_foreign_key_list('devices')").all<{ t: string }>();
    expect(fk.results.map((r) => r.t)).toEqual(["users"]);
    expect((await db.prepare("PRAGMA foreign_key_check").all()).results).toEqual([]);
    await expect(
      db.prepare("INSERT INTO devices (id, user_id, token_hash) VALUES ('D3', 'nobody', 'h3')").run(),
    ).rejects.toThrow();

    // New constraints hold.
    await expect(db.prepare("INSERT INTO users (id, name, role) VALUES ('Z', 'z', 'member')").run()).rejects.toThrow();
    await expect(db.prepare("INSERT INTO users (id, name, role, plan) VALUES ('Z', 'z', 'user', 'gold')").run()).rejects.toThrow();
    await db.prepare("UPDATE users SET google_sub = 'g' WHERE id = 'O'").run();
    await expect(db.prepare("UPDATE users SET google_sub = 'g' WHERE id = 'M'").run()).rejects.toThrow();

    const settings = await db.prepare(
      "SELECT key, value FROM settings WHERE key IN ('free_ai_daily','plus_ai_daily','google_client_ids','flag_hide_threshold') ORDER BY key",
    ).all();
    expect(settings.results).toEqual([
      { key: "flag_hide_threshold", value: "3" },
      { key: "free_ai_daily", value: "3" },
      { key: "google_client_ids", value: "[]" },
      { key: "plus_ai_daily", value: "50" },
    ]);
    expect((await db.prepare("SELECT COUNT(*) AS n FROM account_attempts").first<any>()).n).toBe(0);
  });
});
