import { env, SELF } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import { hashPassword, verifyPassword, randomToken, sha256Hex } from "../src/lib/crypto";
import { createPairingCode } from "../src/auth/devices";
import { clientIpKey } from "../src/lib/ip";

const ORIGIN = "https://tuner.orbitapps.space";
const TOKEN = "test-setup-token";

afterEach(() => vi.useRealTimers());

function cookieValue(res: Response, name: string): string | undefined {
  for (const c of res.headers.getSetCookie()) {
    const m = c.match(new RegExp(`^${name}=([^;]*)`));
    if (m) return m[1];
  }
  return undefined;
}

async function form(
  path: string,
  fields: Record<string, string>,
  opts: { cookie?: string; origin?: string | null } = {},
) {
  const headers: Record<string, string> = {
    "content-type": "application/x-www-form-urlencoded",
  };
  if (opts.origin !== null) headers.origin = opts.origin ?? ORIGIN;
  if (opts.cookie) headers.cookie = opts.cookie;
  return SELF.fetch(ORIGIN + path, {
    method: "POST",
    headers,
    body: new URLSearchParams(fields),
    redirect: "manual",
  });
}

/** GET a pre-auth form page and return its csrf token (cookie + hidden field). */
async function preCsrf(path: string) {
  const res = await SELF.fetch(ORIGIN + path, { redirect: "manual" });
  expect(res.status).toBe(200);
  const cookie = cookieValue(res, "ot_csrf")!;
  const html = await res.text();
  const field = html.match(/name="_csrf" value="([^"]+)"/)![1];
  expect(field).toBe(cookie);
  return { csrf: field, cookie: `ot_csrf=${cookie}` };
}

async function setupOwner(name = "Owner", password = "correct horse battery") {
  const { csrf, cookie } = await preCsrf(`/portal/setup?token=${TOKEN}`);
  return form("/portal/setup", { token: TOKEN, name, password, _csrf: csrf }, { cookie });
}

async function ownerId() {
  const r = await env.DB.prepare("SELECT id FROM users WHERE role='owner'").first<{ id: string }>();
  return r!.id;
}

describe("crypto", () => {
  it("hashes and verifies passwords", async () => {
    const stored = await hashPassword("pw");
    expect(stored).toMatch(/^pbkdf2\$100000\$[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+$/);
    expect(await verifyPassword("pw", stored)).toBe(true);
    expect(await verifyPassword("nope", stored)).toBe(false);
    expect(await verifyPassword("pw", "garbage")).toBe(false);
  });
  it("random tokens are base64url and sha256Hex is hex", async () => {
    const t = randomToken();
    expect(t).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomToken()).not.toBe(t);
    expect(await sha256Hex("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });
});

describe("owner setup", () => {
  it("GET with wrong or missing token is 404", async () => {
    expect((await SELF.fetch(`${ORIGIN}/portal/setup?token=bad`)).status).toBe(404);
    expect((await SELF.fetch(`${ORIGIN}/portal/setup`)).status).toBe(404);
  });

  it("POST with wrong token is 404", async () => {
    const { csrf, cookie } = await preCsrf(`/portal/setup?token=${TOKEN}`);
    const res = await form("/portal/setup", { token: "bad", name: "O", password: "longenough1", _csrf: csrf }, { cookie });
    expect(res.status).toBe(404);
  });

  it("right token creates owner, redirects, sets hardened session cookie; second setup 404", async () => {
    const res = await setupOwner();
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe("/portal");
    const sc = res.headers.getSetCookie().find((c) => c.startsWith("ot_session="))!;
    expect(sc).toMatch(/HttpOnly/i);
    expect(sc).toMatch(/Secure/i);
    expect(sc).toMatch(/SameSite=Lax/i);
    const row = await env.DB.prepare("SELECT role, password_hash FROM users").first<any>();
    expect(row.role).toBe("owner");
    expect(row.password_hash.startsWith("pbkdf2$100000$")).toBe(true);
    // only the hash of the session id is stored
    const raw = cookieValue(res, "ot_session")!;
    const s = await env.DB.prepare("SELECT id_hash FROM sessions").first<any>();
    expect(s.id_hash).toBe(await sha256Hex(raw));
    expect(s.id_hash).not.toBe(raw);

    expect((await SELF.fetch(`${ORIGIN}/portal/setup?token=${TOKEN}`)).status).toBe(404);
    const { cookie: c2 } = await preCsrf("/portal/login");
    const again = await form("/portal/setup", { token: TOKEN, name: "Other", password: "longenough1", _csrf: c2.split("=")[1] }, { cookie: c2 });
    expect(again.status).toBe(404);
  });
});

describe("portal sessions", () => {
  it("/portal redirects to login without a session, serves with one", async () => {
    const r = await SELF.fetch(`${ORIGIN}/portal`, { redirect: "manual" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/login");
    const setup = await setupOwner();
    const sess = cookieValue(setup, "ot_session")!;
    const ok = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie: `ot_session=${sess}` } });
    expect(ok.status).toBe(200);
  });

  it("login works, wrong password does not, logout invalidates", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    const bad = await form("/portal/login", { name: "Owner", password: "wrong", _csrf: csrf }, { cookie });
    expect(bad.status).toBe(401);
    expect(cookieValue(bad, "ot_session")).toBeUndefined();
    const nouser = await form("/portal/login", { name: "Nobody", password: "wrong", _csrf: csrf }, { cookie });
    expect(nouser.status).toBe(401);

    const good = await form("/portal/login", { name: "Owner", password: "correct horse battery", _csrf: csrf }, { cookie });
    expect(good.status).toBe(302);
    expect(good.headers.get("location")).toBe("/portal");
    const sess = cookieValue(good, "ot_session")!;
    const page = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie: `ot_session=${sess}` } });
    expect(page.status).toBe(200);
    const sessCsrf = (await page.text()).match(/name="_csrf" value="([^"]+)"/)![1];

    const out = await form("/portal/logout", { _csrf: sessCsrf }, { cookie: `ot_session=${sess}` });
    expect(out.status).toBe(302);
    const after = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie: `ot_session=${sess}` }, redirect: "manual" });
    expect(after.status).toBe(302);
  });

  it("expired sessions are rejected", async () => {
    const setup = await setupOwner();
    const sess = cookieValue(setup, "ot_session")!;
    await env.DB.prepare("UPDATE sessions SET expires_at = ?").bind(Date.now() - 1).run();
    const r = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie: `ot_session=${sess}` }, redirect: "manual" });
    expect(r.status).toBe(302);
  });

  it("portal POST without matching _csrf is 403 csrf", async () => {
    await setupOwner();
    const { csrf, cookie } = await preCsrf("/portal/login");
    const args = { name: "Owner", password: "correct horse battery" };
    for (const fields of [args, { ...args, _csrf: "wrong" }]) {
      const r = await form("/portal/login", fields, { cookie });
      expect(r.status).toBe(403);
      expect(await r.text()).toContain('data-error="csrf"');
    }
    // field present but cookie missing
    const r2 = await form("/portal/login", { ...args, _csrf: csrf });
    expect(r2.status).toBe(403);
  });

  it("portal POST with foreign or missing Origin is 403 csrf", async () => {
    await setupOwner();
    const { csrf, cookie } = await preCsrf("/portal/login");
    const fields = { name: "Owner", password: "correct horse battery", _csrf: csrf };
    for (const origin of ["https://evil.example", "null", null]) {
      const r = await form("/portal/login", fields, { cookie, origin });
      expect(r.status).toBe(403);
      expect(await r.text()).toContain('data-error="csrf"');
    }
  });

  it("logout needs the session csrf", async () => {
    const setup = await setupOwner();
    const sess = cookieValue(setup, "ot_session")!;
    const r = await form("/portal/logout", { _csrf: "wrong" }, { cookie: `ot_session=${sess}` });
    expect(r.status).toBe(403);
  });
});

describe("clientIpKey", () => {
  it("collapses IPv6 to /64, leaves IPv4 and unknown alone", () => {
    const a = clientIpKey("2001:db8:1:2:aaaa::1");
    expect(a).toBe("2001:db8:1:2::/64");
    expect(clientIpKey("2001:0db8:0001:0002:ffff:ffff:ffff:ffff")).toBe(a);
    expect(clientIpKey("2001:DB8:1:2::a")).toBe(a);
    expect(clientIpKey("2001:db8:1:3::1")).not.toBe(a);
    expect(clientIpKey("198.51.100.4")).toBe("198.51.100.4");
    expect(clientIpKey("unknown")).toBe("unknown");
    expect(clientIpKey("::1")).toBe("0:0:0:0::/64");
    expect(clientIpKey("::ffff:198.51.100.4")).toBe("198.51.100.4");
    expect(clientIpKey("fe80::1%eth0")).toBe("fe80:0:0:0::/64");
  });
});

describe("hardening", () => {
  it("rate limits failed logins: 6th attempt is 429, even with right password", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    const bad = { name: "Owner", password: "wrong", _csrf: csrf };
    for (let i = 0; i < 5; i++) {
      expect((await form("/portal/login", bad, { cookie })).status).toBe(401);
    }
    expect((await form("/portal/login", bad, { cookie })).status).toBe(429);
    const good = await form("/portal/login", { ...bad, password: "correct horse battery" }, { cookie });
    expect(good.status).toBe(429);
    expect(cookieValue(good, "ot_session")).toBeUndefined();
  });

  it("rate limit also applies per client IP across names", async () => {
    await setupOwner();
    const { csrf, cookie } = await preCsrf("/portal/login");
    const hdr = { cookie };
    for (let i = 0; i < 5; i++) {
      const r = await SELF.fetch(ORIGIN + "/portal/login", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie, "cf-connecting-ip": "203.0.113.9" },
        body: new URLSearchParams({ name: "n" + i, password: "x", _csrf: csrf }),
        redirect: "manual",
      });
      expect(r.status).toBe(401);
    }
    void hdr;
    const r = await SELF.fetch(ORIGIN + "/portal/login", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie, "cf-connecting-ip": "203.0.113.9", accept: "application/json" },
      body: new URLSearchParams({ name: "Owner", password: "correct horse battery", _csrf: csrf }),
      redirect: "manual",
    });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
  });

  it("successful login clears the name counter", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    for (let i = 0; i < 4; i++) {
      await form("/portal/login", { name: "Owner", password: "wrong", _csrf: csrf }, { cookie });
    }
    const ok = await form("/portal/login", { name: "Owner", password: "correct horse battery", _csrf: csrf }, { cookie });
    expect(ok.status).toBe(302);
    const left = await env.DB.prepare("SELECT key FROM login_attempts").all<any>();
    expect(left.results.filter((r) => r.key.startsWith("pair:"))).toEqual([]);
    expect(left.results.filter((r) => r.key.startsWith("name:"))).toEqual([]);
  });

  async function loginFrom(ip: string, name: string, password: string, csrf: string, cookie: string) {
    return SELF.fetch(ORIGIN + "/portal/login", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie, "cf-connecting-ip": ip },
      body: new URLSearchParams({ name, password, _csrf: csrf }),
      redirect: "manual",
    });
  }

  it("lockout is per name+ip: attacker IP cannot lock the owner out elsewhere", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    for (let i = 0; i < 5; i++) {
      expect((await loginFrom("198.51.100.1", "Owner", "wrong", csrf, cookie)).status).toBe(401);
    }
    expect((await loginFrom("198.51.100.1", "Owner", "wrong", csrf, cookie)).status).toBe(429);
    const other = await loginFrom("198.51.100.2", "Owner", "correct horse battery", csrf, cookie);
    expect(other.status).toBe(302);
  });

  it("concurrent bad logins cannot exceed the cap (attempt reserved before verify)", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    const rs = await Promise.all(
      Array.from({ length: 10 }, () => loginFrom("198.51.100.7", "Owner", "wrong", csrf, cookie)),
    );
    const statuses = rs.map((r) => r.status);
    expect(statuses.filter((s) => s === 429).length).toBeGreaterThanOrEqual(5);
    expect(statuses.filter((s) => s === 401).length).toBeLessThanOrEqual(5);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE key LIKE 'ip:%'").first<any>();
    expect(n.n).toBe(10);
  });

  it("successful login frees its reserved attempt rows", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    for (let i = 0; i < 5; i++) await loginFrom("198.51.100.3", "Owner", "wrong", csrf, cookie);
    // a success from a different IP releases its own reservation and leaves no rows for that IP
    const ok = await loginFrom("198.51.100.4", "Owner", "correct horse battery", csrf, cookie);
    expect(ok.status).toBe(302);
    const rows = await env.DB.prepare("SELECT key FROM login_attempts WHERE key LIKE '%198.51.100.4%'").all();
    expect(rows.results.length).toBe(0); // neither ip: nor pair: rows remain
    const other = await env.DB.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE key LIKE '%198.51.100.3%'").first<any>();
    expect(other.n).toBe(10); // the failed IP's rows (pair + ip) are untouched
  });

  it("rotating addresses inside one IPv6 /64 does not dodge the limit", async () => {
    await setupOwner("Owner", "correct horse battery");
    const { csrf, cookie } = await preCsrf("/portal/login");
    for (let i = 0; i < 5; i++) {
      expect((await loginFrom("2001:db8:1:2::a", "N" + i, "x", csrf, cookie)).status).toBe(401);
    }
    expect((await loginFrom("2001:db8:1:2::b", "Other", "x", csrf, cookie)).status).toBe(429);
    expect((await loginFrom("2001:db8:1:3::b", "Other", "x", csrf, cookie)).status).toBe(401);
  });

  it("a user cannot log into the portal", async () => {
    await env.DB.prepare("INSERT INTO users (id, name, role, password_hash, created_at) VALUES ('M1','Mem','user',?,1)")
      .bind(await hashPassword("member-password"))
      .run();
    const { csrf, cookie } = await preCsrf("/portal/login");
    const r = await form("/portal/login", { name: "Mem", password: "member-password", _csrf: csrf }, { cookie });
    expect(r.status).toBe(401);
    expect(cookieValue(r, "ot_session")).toBeUndefined();
  });

  it("setup POST with bad CSRF is 403", async () => {
    const { cookie } = await preCsrf(`/portal/setup?token=${TOKEN}`);
    const r = await form("/portal/setup", { token: TOKEN, name: "O", password: "longenough1", _csrf: "bad" }, { cookie });
    expect(r.status).toBe(403);
    expect(await env.DB.prepare("SELECT 1 FROM users").first()).toBeNull();
  });

  it("session csrf is required for every non-login portal POST; pre-login cookie csrf does not work", async () => {
    const setup = await setupOwner();
    const sess = cookieValue(setup, "ot_session")!;
    const { csrf: pre, cookie: preCookie } = await preCsrf("/portal/login");
    const r = await form("/portal/anything", { _csrf: pre }, { cookie: `ot_session=${sess}; ${preCookie}` });
    expect(r.status).toBe(403);
    const r2 = await form("/portal/logout", { _csrf: pre }, { cookie: `ot_session=${sess}; ${preCookie}` });
    expect(r2.status).toBe(403);
  });

  it("X-CSRF header is accepted against the session csrf", async () => {
    const setup = await setupOwner();
    const sess = cookieValue(setup, "ot_session")!;
    const sc = (await env.DB.prepare("SELECT csrf FROM sessions").first<any>()).csrf;
    const r = await SELF.fetch(ORIGIN + "/portal/logout", {
      method: "POST",
      headers: { origin: ORIGIN, cookie: `ot_session=${sess}`, "x-csrf": sc },
      redirect: "manual",
    });
    expect(r.status).toBe(302);
  });

  it("logout without a session clears cookie and redirects when Origin is ok", async () => {
    const r = await form("/portal/logout", {}, { cookie: "ot_session=stale" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/login");
    const bad = await form("/portal/logout", {}, { origin: "https://evil.example" });
    expect(bad.status).toBe(403);
  });

  it("session cookie path is /", async () => {
    const setup = await setupOwner();
    const sc = setup.headers.getSetCookie().find((c) => c.startsWith("ot_session="))!;
    expect(sc).toMatch(/Path=\/(;|$)/);
  });
});

describe("pairing and device tokens", () => {
  async function pair(code: string, deviceName = "Pixel") {
    return SELF.fetch(`${ORIGIN}/v1/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code, deviceName }),
    });
  }

  it("creates code with link, redeems once, 410 on reuse", async () => {
    await setupOwner();
    const uid = await ownerId();
    const { code, link, expiresAt } = await createPairingCode(env, uid);
    expect(link).toBe(`orbittuner://pair?code=${code}&host=tuner.orbitapps.space`);
    expect(expiresAt - Date.now()).toBeGreaterThan(9 * 60_000);
    const stored = await env.DB.prepare("SELECT code_hash FROM pairing_codes").first<any>();
    expect(stored.code_hash).toBe(await sha256Hex(code));

    const res = await pair(code);
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.deviceToken.length).toBeGreaterThanOrEqual(43);
    expect(body.user).toEqual({ id: uid, name: "Owner", role: "owner" });
    const dev = await env.DB.prepare("SELECT token_hash FROM devices").first<any>();
    expect(dev.token_hash).toBe(await sha256Hex(body.deviceToken));

    const again = await pair(code);
    expect(again.status).toBe(410);
    expect(await again.json()).toEqual({ error: "code_used" });
  });

  it("racing redeems: exactly one wins", async () => {
    await setupOwner();
    const { code } = await createPairingCode(env, await ownerId());
    const rs = await Promise.all([pair(code, "A"), pair(code, "B")]);
    expect(rs.map((r) => r.status).sort()).toEqual([200, 410]);
    const loser = rs.find((r) => r.status === 410)!;
    expect(await loser.json()).toEqual({ error: "code_used" });
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices").first<any>();
    expect(n.n).toBe(1);
  });

  it("unknown code is 400 invalid_code", async () => {
    const r = await pair("nonsense");
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "invalid_code" });
    const r2 = await SELF.fetch(`${ORIGIN}/v1/pair`, { method: "POST", body: "not json" });
    expect(r2.status).toBe(400);
  });

  it("expired code is 410 code_expired", async () => {
    await setupOwner();
    const uid = await ownerId();
    vi.useFakeTimers({ toFake: ["Date"] });
    const { code } = await createPairingCode(env, uid);
    vi.setSystemTime(Date.now() + 11 * 60_000);
    const r = await pair(code);
    expect(r.status).toBe(410);
    expect(await r.json()).toEqual({ error: "code_expired" });
  });

  it("requireDevice: no/unknown token 401, valid ok, revoked 401 token_revoked", async () => {
    await setupOwner();
    const uid = await ownerId();
    const none = await SELF.fetch(`${ORIGIN}/v1/search`);
    expect(none.status).toBe(401);
    expect(await none.json()).toEqual({ error: "unauthorized" });
    const unknownPath = await SELF.fetch(`${ORIGIN}/v1/whatever`);
    expect(unknownPath.status).toBe(401);
    const bogus = await SELF.fetch(`${ORIGIN}/v1/search`, { headers: { authorization: "Bearer bogus" } });
    expect(bogus.status).toBe(401);

    const { code } = await createPairingCode(env, uid);
    const { deviceToken } = (await (await pair(code)).json()) as any;
    const auth = { authorization: `Bearer ${deviceToken}` };
    const ok = await SELF.fetch(`${ORIGIN}/v1/search`, { headers: auth });
    expect(ok.status).not.toBe(401); // authenticated (route itself arrives in Task 4)
    const seen = await env.DB.prepare("SELECT last_seen_at FROM devices").first<any>();
    expect(seen.last_seen_at).toBeGreaterThan(0);

    await env.DB.prepare("UPDATE devices SET revoked_at = ?").bind(Date.now()).run();
    const rev = await SELF.fetch(`${ORIGIN}/v1/search`, { headers: auth });
    expect(rev.status).toBe(401);
    expect(await rev.json()).toEqual({ error: "token_revoked" });
  });
});
