import { createExecutionContext, env } from "cloudflare:test";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { __googleTest, mergeInto, resetGoogleKeyCache } from "../src/auth/google";
import { ulid } from "../src/lib/ulid";
import { clearSongs, makeUser, ORIGIN } from "./helpers";

const CERTS = "https://www.googleapis.com/oauth2/v3/certs";
const CLIENT = "client-1.apps.googleusercontent.com";

type Pair = { priv: CryptoKey; jwk: JsonWebKey & { kid: string } };
let k1: Pair;
let k2: Pair;
let rogue: Pair;
let published: Pair[];
let certFetches: number;

async function makePair(kid: string): Promise<Pair> {
  const kp = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const jwk = (await crypto.subtle.exportKey("jwk", kp.publicKey)) as JsonWebKey;
  return { priv: kp.privateKey, jwk: { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", use: "sig", kid } };
}

const b64url = (s: string | Uint8Array) => {
  const bytes = typeof s === "string" ? new TextEncoder().encode(s) : s;
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function sign(claims: Record<string, unknown>, opts: { key?: Pair; kid?: string; alg?: string } = {}) {
  const key = opts.key ?? k1;
  const h = b64url(JSON.stringify({ alg: opts.alg ?? "RS256", kid: opts.kid ?? key.jwk.kid, typ: "JWT" }));
  const p = b64url(JSON.stringify(claims));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key.priv, new TextEncoder().encode(`${h}.${p}`));
  return `${h}.${p}.${b64url(new Uint8Array(sig))}`;
}

const nowS = () => Math.floor(Date.now() / 1000);
function claims(over: Record<string, unknown> = {}) {
  return {
    iss: "https://accounts.google.com",
    aud: CLIENT,
    sub: "1000001",
    email: "someone@example.com",
    email_verified: true,
    iat: nowS() - 10,
    exp: nowS() + 3600,
    ...over,
  };
}

function google(body: unknown, opts: { token?: string; ip?: string } = {}) {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "cf-connecting-ip": opts.ip ?? "203.0.113.40",
  };
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  return worker.fetch!(
    new Request(ORIGIN + "/v1/accounts/google", {
      method: "POST",
      headers,
      body: typeof body === "string" ? body : JSON.stringify(body),
    }) as any,
    env as any,
    createExecutionContext(),
  );
}

function get(path: string, token: string) {
  return worker.fetch!(
    new Request(ORIGIN + path, { headers: { authorization: `Bearer ${token}` } }) as any,
    env as any,
    createExecutionContext(),
  );
}

async function expectInvalid(r: Response) {
  expect(r.status).toBe(401);
  expect(await r.json()).toEqual({ error: "invalid_google_token" });
}

const setClientIds = (v: string) =>
  env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('google_client_ids', ?)").bind(v).run();

beforeAll(async () => {
  k1 = await makePair("k1");
  k2 = await makePair("k2");
  rogue = await makePair("k1"); // same kid as k1, different key
});

beforeEach(async () => {
  await clearSongs();
  await resetGoogleKeyCache();
  await setClientIds(JSON.stringify([CLIENT, "other-client.apps.googleusercontent.com"]));
  await env.DB.batch([
    env.DB.prepare("DELETE FROM account_attempts"),
    env.DB.prepare("UPDATE users SET google_sub = NULL"),
    env.DB.prepare("DELETE FROM favourites"),
    env.DB.prepare("DELETE FROM playlist_items"),
    env.DB.prepare("DELETE FROM playlists"),
  ]);
  published = [k1];
  certFetches = 0;
  const realFetch = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url === CERTS) {
      certFetches++;
      return new Response(JSON.stringify({ keys: published.map((p) => p.jwk) }), {
        headers: { "content-type": "application/json", "cache-control": "public, max-age=20000" },
      });
    }
    return realFetch(input as any, init);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("Google ID token verification", () => {
  it("rejects a wrong aud", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ aud: "evil.apps.googleusercontent.com" })) }));
  });

  it("rejects an aud given as an array", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ aud: [CLIENT] })) }));
  });

  it("rejects a wrong iss", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ iss: "https://evil.example.com" })) }));
  });

  it("rejects an expired token, but allows 60 s of clock skew", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ exp: nowS() - 120 })) }));
    const r = await google({ idToken: await sign(claims({ exp: nowS() - 20 })) });
    expect(r.status).toBe(201);
  });

  it("rejects a token issued in the future", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ iat: nowS() + 3600 })) }));
  });

  it("rejects a bad signature (other key, same kid) and a tampered payload", async () => {
    await expectInvalid(await google({ idToken: await sign(claims(), { key: rogue }) }));
    const good = await sign(claims());
    const [h, , s] = good.split(".");
    const forged = `${h}.${b64url(JSON.stringify(claims({ sub: "victim" })))}.${s}`;
    await expectInvalid(await google({ idToken: forged }));
  });

  it("rejects alg none / HS256 and garbage", async () => {
    const p = b64url(JSON.stringify(claims()));
    await expectInvalid(await google({ idToken: `${b64url(JSON.stringify({ alg: "none", kid: "k1" }))}.${p}.` }));
    await expectInvalid(await google({ idToken: await sign(claims(), { alg: "HS256" }) }));
    await expectInvalid(await google({ idToken: "not.a.jwt" }));
    await expectInvalid(await google({ idToken: "x" }));
  });

  it("rejects an unknown kid after one refetch", async () => {
    await expectInvalid(await google({ idToken: await sign(claims(), { key: k2 }) }));
    expect(certFetches).toBe(2); // initial load + one forced refetch
    // A second unknown-kid token inside the refetch cooldown does not hit Google again.
    await expectInvalid(await google({ idToken: await sign(claims(), { key: k2, kid: "k9" }) }));
    expect(certFetches).toBe(2);
  });

  it("rejects an unverified or missing email_verified", async () => {
    await expectInvalid(await google({ idToken: await sign(claims({ email_verified: false })) }));
    await expectInvalid(await google({ idToken: await sign(claims({ email_verified: "true" })) }));
    const { email_verified: _drop, ...rest } = claims();
    await expectInvalid(await google({ idToken: await sign(rest) }));
  });

  it("accepts iss without https and caches the JWKS", async () => {
    expect((await google({ idToken: await sign(claims({ iss: "accounts.google.com" })) })).status).toBe(201);
    expect((await google({ idToken: await sign(claims()) })).status).toBe(200);
    expect(certFetches).toBe(1);
  });

  it("picks up a rotated key by refetching on an unknown kid", async () => {
    expect((await google({ idToken: await sign(claims()) })).status).toBe(201);
    published = [k1, k2];
    expect((await google({ idToken: await sign(claims(), { key: k2 }) })).status).toBe(200);
    expect(certFetches).toBe(2);
  });

  it("a forced refetch does not piggyback on an in-flight cached load", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let n = 0;
    vi.mocked(globalThis.fetch).mockImplementation(async (input: any) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url !== CERTS) throw new Error("unexpected fetch " + url);
      certFetches++;
      const call = ++n;
      if (call === 1) await gate; // the normal load is still in flight
      const keys = call === 1 ? [k1.jwk] : [k1.jwk, k2.jwk];
      return new Response(JSON.stringify({ keys }), { headers: { "cache-control": "max-age=3600" } });
    });
    const normal = __googleTest.loadKeys(false);
    for (let i = 0; i < 50 && certFetches < 1; i++) await new Promise((r) => setTimeout(r, 2));
    expect(certFetches).toBe(1); // the normal load is at the network, held by the gate
    const forced = __googleTest.loadKeys(true);
    await new Promise((r) => setTimeout(r, 10));
    expect(certFetches).toBe(2); // the forced load went to the network itself
    release();
    const [, f] = await Promise.all([normal, forced]);
    expect(f.keys.has("k2")).toBe(true);
    // The fresher forced key set is kept even though the normal load finished later.
    expect((await __googleTest.loadKeys(false)).keys.has("k2")).toBe(true);
  });

  it("keeps using expired keys for up to 24 h when Google cannot be reached", async () => {
    expect((await google({ idToken: await sign(claims({ sub: "s-grace" })) })).status).toBe(201);
    const t0 = Date.now();
    vi.mocked(globalThis.fetch).mockImplementation(async () => {
      certFetches++;
      return new Response("down", { status: 500 });
    });
    const spy = vi.spyOn(Date, "now").mockReturnValue(t0 + 20_000 * 1000 + 2 * 3600_000); // 2 h past expiry
    expect((await google({ idToken: await sign(claims({ sub: "s-grace" })) })).status).toBe(200);
    spy.mockReturnValue(t0 + 20_000 * 1000 + 25 * 3600_000); // beyond the grace period
    const r = await google({ idToken: await sign(claims({ sub: "s-grace" })) });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "google_unavailable" });
  });

  it("an empty google_client_ids answers 503 google_not_configured", async () => {
    await setClientIds("[]");
    const r = await google({ idToken: await sign(claims()) });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "google_not_configured" });
    await setClientIds("not json");
    expect((await google({ idToken: await sign(claims()) })).status).toBe(503);
  });

  it("rejects a missing idToken with 400 and an oversized body with 413", async () => {
    expect((await google({})).status).toBe(400);
    expect((await google({ idToken: "x".repeat(20_000) })).status).toBe(413);
  });
});

describe("POST /v1/accounts/google with a device token", () => {
  it("links a new sub to the current user", async () => {
    const a = await makeUser("user", "anon-a");
    const r = await google({ idToken: await sign(claims({ sub: "s-link" })) }, { token: a.token });
    expect(r.status).toBe(200);
    const body = await r.json<any>();
    expect(body.deviceToken).toBeUndefined();
    expect(body.user).toMatchObject({ id: a.id, role: "user", plan: "free", google: { email: "someone@example.com" } });
    const row = await env.DB.prepare("SELECT google_sub, email FROM users WHERE id = ?").bind(a.id).first<any>();
    expect(row).toEqual({ google_sub: "s-link", email: "someone@example.com" });
    const me = await (await get("/v1/me", a.token)).json<any>();
    expect(me.google).toEqual({ email: "someone@example.com" });
  });

  it("re-linking the same sub is a no-op", async () => {
    const a = await makeUser("user", "anon-b");
    await google({ idToken: await sign(claims({ sub: "s-same" })) }, { token: a.token });
    const r = await google({ idToken: await sign(claims({ sub: "s-same" })) }, { token: a.token });
    expect(r.status).toBe(200);
    const body = await r.json<any>();
    expect(body.deviceToken).toBeUndefined();
    expect(body.user.id).toBe(a.id);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE google_sub = 's-same'").first<any>();
    expect(n.n).toBe(1);
  });

  it("refuses to switch an already-linked account to another Google identity", async () => {
    const a = await makeUser("user", "anon-c");
    await google({ idToken: await sign(claims({ sub: "s-first" })) }, { token: a.token });
    const r = await google({ idToken: await sign(claims({ sub: "s-second" })) }, { token: a.token });
    expect(r.status).toBe(409);
    expect(await r.json()).toEqual({ error: "google_already_linked" });
    const row = await env.DB.prepare("SELECT google_sub FROM users WHERE id = ?").bind(a.id).first<any>();
    expect(row.google_sub).toBe("s-first");
  });

  it("an owner can link Google too", async () => {
    const o = await makeUser("owner", "owner-g");
    const r = await google({ idToken: await sign(claims({ sub: "s-owner" })) }, { token: o.token });
    expect(r.status).toBe(200);
    expect((await r.json<any>()).user).toMatchObject({ id: o.id, role: "owner" });
  });

  it("signing in with another account's sub merges favourites and playlists (newer wins) and re-binds the device", async () => {
    const x = await makeUser("user", "x-user");
    const a = await makeUser("user", "anon-d");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x', email = 'old@example.com' WHERE id = ?").bind(x.id).run();
    const p1 = ulid();
    const p2 = ulid();
    const song = ulid();
    const job = ulid();
    const report = ulid();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO favourites VALUES (?, 'song-1', 1, 100), (?, 'song-2', 1, 300)").bind(x.id, x.id),
      env.DB.prepare("INSERT INTO favourites VALUES (?, 'song-1', 0, 200), (?, 'song-2', 0, 200), (?, 'song-3', 1, 50)")
        .bind(a.id, a.id, a.id),
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at, modified_at) VALUES (?, ?, 'X list', 0, 100, 5)")
        .bind(p1, x.id),
      env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at, modified_at) VALUES (?, ?, 'A list', 0, 150, 3)")
        .bind(p2, a.id),
      env.DB.prepare("INSERT INTO playlist_items (playlist_id, song_id, position, settings) VALUES (?, 'song-3', 'a', '{}')").bind(p2),
      env.DB.prepare(
        "INSERT INTO songs (id, title, chordpro, source, status, created_by, created_at, updated_at) VALUES (?, 'Mine', '{title: Mine}', 'import', 'review', ?, 1, 1)",
      ).bind(song, a.id),
      env.DB.prepare("INSERT INTO import_jobs (id, user_id, created_at, total, status) VALUES (?, ?, 1, 1, 'queued')").bind(job, a.id),
      env.DB.prepare("INSERT INTO reports (id, song_id, user_id, note, created_at) VALUES (?, ?, ?, 'wrong chords', 1)").bind(report, song, a.id),
      env.DB.prepare("INSERT INTO user_ai_usage (user_id, day, count) VALUES (?, ?, 1), (?, ?, 2)")
        .bind(a.id, new Date().toISOString().slice(0, 10), x.id, new Date().toISOString().slice(0, 10)),
    ]);

    const before = Date.now();
    const r = await google({ idToken: await sign(claims({ sub: "s-x", email: "new@example.com" })) }, { token: a.token });
    expect(r.status).toBe(200);
    const body = await r.json<any>();
    expect(body.deviceToken).toBe(a.token); // identity changed: same token, now bound to X
    expect(body.user).toMatchObject({ id: x.id, role: "user", google: { email: "new@example.com" } });

    const favs = await env.DB.prepare("SELECT user_id, song_id, favourite, updated_at FROM favourites ORDER BY user_id = ?, song_id")
      .bind(x.id).all<any>();
    expect(favs.results).toEqual([
      { user_id: x.id, song_id: "song-1", favourite: 0, updated_at: 200 },
      { user_id: x.id, song_id: "song-2", favourite: 1, updated_at: 300 },
      { user_id: x.id, song_id: "song-3", favourite: 1, updated_at: 50 },
    ]);

    const pl = await env.DB.prepare("SELECT id, user_id, modified_at FROM playlists ORDER BY id").all<any>();
    const moved = pl.results.find((p: any) => p.id === p2);
    expect(moved.user_id).toBe(x.id);
    expect(moved.modified_at).toBeGreaterThanOrEqual(before);
    // X's other devices pull it with their old cursor (5).
    const pulled = await (await get("/v1/me/playlists?since=5", x.token)).json<any>();
    expect(pulled.items.map((p: any) => p.id)).toEqual([p2]);
    expect(pulled.items[0].items).toEqual([{ songId: "song-3", position: "a", settings: {} }]);

    const s = await env.DB.prepare("SELECT created_by FROM songs WHERE id = ?").bind(song).first<any>();
    expect(s.created_by).toBe(x.id);
    const j = await env.DB.prepare("SELECT user_id FROM import_jobs WHERE id = ?").bind(job).first<any>();
    expect(j.user_id).toBe(x.id);
    const rep = await env.DB.prepare("SELECT user_id FROM reports WHERE id = ?").bind(report).first<any>();
    expect(rep.user_id).toBe(x.id);

    const me = await (await get("/v1/me", a.token)).json<any>();
    expect(me.id).toBe(x.id);
    expect(me.google).toEqual({ email: "new@example.com" });
    // Today's AI usage is summed into X (now Google-linked: the Free tier of 3).
    expect(me).toMatchObject({ aiDailyLimit: 3, aiLeftToday: 0 });
    const usage = await env.DB.prepare("SELECT user_id, count FROM user_ai_usage WHERE user_id IN (?, ?)").bind(x.id, a.id).all<any>();
    expect(usage.results).toEqual([{ user_id: x.id, count: 3 }]);
    const gone = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(a.id).first();
    expect(gone).toBeNull();
  });

  it("merge guard: a device no longer A's turns the batch into 409 conflict with nothing moved or deleted", async () => {
    const x = await makeUser("user", "xg1");
    const y = await makeUser("user", "yg1");
    const a = await makeUser("user", "anon-g1");
    await env.DB.prepare("UPDATE users SET google_sub = 's-g1' WHERE id = ?").bind(x.id).run();
    await env.DB.prepare("INSERT INTO favourites VALUES (?, 'song-9', 1, 10)").bind(a.id).run();
    const dev = await env.DB.prepare("SELECT id FROM devices WHERE user_id = ?").bind(a.id).first<any>();
    // Between resolve and merge, D was re-bound elsewhere: A now has no devices at all.
    await env.DB.prepare("UPDATE devices SET user_id = ? WHERE id = ?").bind(y.id, dev.id).run();
    await expect(mergeInto(env.DB, a.id, x.id, dev.id, { sub: "s-g1", email: "e@example.com", emailVerified: true }))
      .rejects.toMatchObject({ status: 409, code: "conflict" });
    expect(await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(a.id).first()).not.toBeNull();
    const fav = await env.DB.prepare("SELECT user_id FROM favourites WHERE song_id = 'song-9'").all<any>();
    expect(fav.results).toEqual([{ user_id: a.id }]);
  });

  it("merge guard: X disabled between resolve and merge is 409 conflict with nothing moved", async () => {
    const x = await makeUser("user", "xg2");
    const a = await makeUser("user", "anon-g2");
    await env.DB.prepare("UPDATE users SET google_sub = 's-g2', disabled_at = 1 WHERE id = ?").bind(x.id).run();
    const p = ulid();
    await env.DB.prepare("INSERT INTO playlists (id, user_id, name, deleted, updated_at, modified_at) VALUES (?, ?, 'A', 0, 1, 1)")
      .bind(p, a.id).run();
    const dev = await env.DB.prepare("SELECT id FROM devices WHERE user_id = ?").bind(a.id).first<any>();
    await expect(mergeInto(env.DB, a.id, x.id, dev.id, { sub: "s-g2", email: "e@example.com", emailVerified: true }))
      .rejects.toMatchObject({ status: 409, code: "conflict" });
    expect(await env.DB.prepare("SELECT user_id FROM playlists WHERE id = ?").bind(p).first<any>()).toEqual({ user_id: a.id });
    expect(await env.DB.prepare("SELECT user_id FROM devices WHERE id = ?").bind(dev.id).first<any>()).toEqual({ user_id: a.id });
    expect(await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(a.id).first()).not.toBeNull();
  });

  it("keeps the anonymous user when it still has another device", async () => {
    const x = await makeUser("user", "x2");
    const a = await makeUser("user", "anon-e");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x2' WHERE id = ?").bind(x.id).run();
    const other = await env.DB.prepare(
      "INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES (?, ?, 'tablet', ?, 1)",
    ).bind(ulid(), a.id, "hash-" + ulid()).run();
    expect(other.success).toBe(true);
    const r = await google({ idToken: await sign(claims({ sub: "s-x2" })) }, { token: a.token });
    expect(r.status).toBe(200);
    const kept = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(a.id).first();
    expect(kept).not.toBeNull();
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(a.id).first<any>();
    expect(n.n).toBe(1);
  });

  it("a merge moves A's song flags to X; a song both flagged keeps X's flag; X's own songs get no self-flag", async () => {
    const x = await makeUser("user", "x-flags");
    const a = await makeUser("user", "anon-flags");
    await env.DB.prepare("UPDATE users SET google_sub = 's-xf' WHERE id = ?").bind(x.id).run();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM song_flags"),
      env.DB.prepare(
        "INSERT INTO song_flags (song_id, user_id, reason, note, created_at) VALUES ('s1', ?, 'wrong_chords', 'x note', 1), ('s1', ?, 'other', 'a note', 2), ('s2', ?, 'not_a_song', NULL, 3), ('s3', ?, 'other', NULL, 4)",
      ).bind(x.id, a.id, a.id, a.id),
      env.DB.prepare(
        "INSERT INTO songs (id, title, chordpro, source, status, created_by, created_at, updated_at) VALUES ('s3', 'X Own', '{title: X Own}', 'owner', 'published', ?, 1, 1)",
      ).bind(x.id),
    ]);
    const r = await google({ idToken: await sign(claims({ sub: "s-xf" })) }, { token: a.token });
    expect(r.status).toBe(200);
    const rows = await env.DB.prepare("SELECT song_id, user_id, reason, note FROM song_flags ORDER BY song_id").all<any>();
    expect(rows.results).toEqual([
      { song_id: "s1", user_id: x.id, reason: "wrong_chords", note: "x note" },
      { song_id: "s2", user_id: x.id, reason: "not_a_song", note: null },
    ]);
  });

  it("refuses to merge an owner/admin account into another account", async () => {
    const x = await makeUser("user", "x3");
    const adm = await makeUser("admin", "adm");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x3' WHERE id = ?").bind(x.id).run();
    const r = await google({ idToken: await sign(claims({ sub: "s-x3" })) }, { token: adm.token });
    expect(r.status).toBe(409);
    expect(await r.json()).toEqual({ error: "google_linked_elsewhere" });
    const me = await (await get("/v1/me", adm.token)).json<any>();
    expect(me.id).toBe(adm.id);
  });

  it("will not merge into a disabled account", async () => {
    const x = await makeUser("user", "x4");
    const a = await makeUser("user", "anon-f");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x4', disabled_at = 1 WHERE id = ?").bind(x.id).run();
    const r = await google({ idToken: await sign(claims({ sub: "s-x4" })) }, { token: a.token });
    expect(r.status).toBe(403);
    expect(await r.json()).toEqual({ error: "account_disabled" });
    const dev = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(a.id).first<any>();
    expect(dev.n).toBe(1);
  });

  it("an invalid Google token never links or merges", async () => {
    const x = await makeUser("user", "x5");
    const a = await makeUser("user", "anon-g");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x5' WHERE id = ?").bind(x.id).run();
    await expectInvalid(await google({ idToken: await sign(claims({ sub: "s-x5", aud: "evil" })) }, { token: a.token }));
    const me = await (await get("/v1/me", a.token)).json<any>();
    expect(me.id).toBe(a.id);
  });

  it("a bad device token is 401 unauthorized, not a tokenless sign-in", async () => {
    const r = await google({ idToken: await sign(claims()) }, { token: "A".repeat(43) });
    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "unauthorized" });
  });
});

describe("POST /v1/accounts/google without a device token", () => {
  it("on a new device returns a new token for the same linked user", async () => {
    const x = await makeUser("user", "x6");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x6' WHERE id = ?").bind(x.id).run();
    const r = await google({ idToken: await sign(claims({ sub: "s-x6" })), deviceName: "Fold" });
    expect(r.status).toBe(200);
    expect(r.headers.get("cache-control")).toBe("no-store");
    const body = await r.json<any>();
    expect(body.deviceToken).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    expect(body.deviceToken).not.toBe(x.token);
    expect(body.user.id).toBe(x.id);
    const me = await (await get("/v1/me", body.deviceToken)).json<any>();
    expect(me.id).toBe(x.id);
    const dev = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ? AND name = 'Fold'").bind(x.id).first<any>();
    expect(dev.n).toBe(1);
  });

  it("creates a new user linked to an unknown sub, and finds it again next time", async () => {
    const r = await google({ idToken: await sign(claims({ sub: "s-new" })) });
    expect(r.status).toBe(201);
    const body = await r.json<any>();
    expect(body.user).toMatchObject({ role: "user", plan: "free", google: { email: "someone@example.com" } });
    const row = await env.DB.prepare("SELECT role, google_sub, password_hash FROM users WHERE id = ?").bind(body.user.id).first<any>();
    expect(row).toEqual({ role: "user", google_sub: "s-new", password_hash: null });
    const again = await google({ idToken: await sign(claims({ sub: "s-new" })) });
    expect(again.status).toBe(200);
    expect((await again.json<any>()).user.id).toBe(body.user.id);
  });

  it("is rate limited per IP like anonymous sign-up", async () => {
    for (let i = 0; i < 5; i++) {
      expect((await google({ idToken: await sign(claims({ sub: `s-rl-${i}` })) }, { ip: "198.51.100.77" })).status).toBe(201);
    }
    const r = await google({ idToken: await sign(claims({ sub: "s-rl-6" })) }, { ip: "198.51.100.77" });
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ error: "rate_limited" });
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE google_sub = 's-rl-6'").first<any>();
    expect(n.n).toBe(0);
    expect((await google({ idToken: await sign(claims({ sub: "s-rl-7" })) }, { ip: "198.51.100.78" })).status).toBe(201);
  });

  it("refuses tokenless sign-in to an owner or admin account (they use the portal)", async () => {
    for (const role of ["owner", "admin"] as const) {
      const o = await makeUser(role, `${role}-tl`);
      await env.DB.prepare("UPDATE users SET google_sub = ? WHERE id = ?").bind(`s-tl-${role}`, o.id).run();
      const r = await google({ idToken: await sign(claims({ sub: `s-tl-${role}` })) });
      expect(r.status).toBe(403);
      expect(await r.json()).toEqual({ error: "admin_use_portal" });
      const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(o.id).first<any>();
      expect(n.n).toBe(1);
    }
  });

  it("refuses a disabled linked account", async () => {
    const x = await makeUser("user", "x7");
    await env.DB.prepare("UPDATE users SET google_sub = 's-x7', disabled_at = 1 WHERE id = ?").bind(x.id).run();
    const r = await google({ idToken: await sign(claims({ sub: "s-x7" })) });
    expect(r.status).toBe(403);
  });
});
