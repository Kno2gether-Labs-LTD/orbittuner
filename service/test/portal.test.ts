import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { ORIGIN, clearSongs, makeUser } from "./helpers";
import { upsertSong } from "../src/songs/repo";
import { sha256Hex } from "../src/lib/crypto";
import amazing from "./fixtures/chordpro/amazing-grace.cho?raw";
import unknownDir from "./fixtures/chordpro/unknown-directives.cho?raw";
import unicode from "./fixtures/chordpro/unicode.cho?raw";
import noTitle from "./fixtures/chordpro/no-title.cho?raw";
import expectedPlain from "./fixtures/chordpro/chords-over-lyrics.expected.cho?raw";
import app from "../src/index";
import { parseChordPro } from "../src/chordpro/parse";
// Executes the browser script as a module (no DOM in workerd): it publishes its parser.
import "../src/portal/portal.client.js?parity";

const TOKEN = "test-setup-token";

function cookieValue(res: Response, name: string): string | undefined {
  for (const c of res.headers.getSetCookie()) {
    const m = c.match(new RegExp(`^${name}=([^;]*)`));
    if (m) return m[1];
  }
  return undefined;
}

const csrfOf = (html: string) => html.match(/name="_csrf" value="([^"]+)"/)![1];

type Session = { cookie: string; csrf: string; ownerId: string };

/** Set up the owner through the real setup form and return the session cookie + its csrf. */
async function login(): Promise<Session> {
  const pre = await SELF.fetch(`${ORIGIN}/portal/setup?token=${TOKEN}`);
  const preCookie = `ot_csrf=${cookieValue(pre, "ot_csrf")}`;
  const res = await SELF.fetch(`${ORIGIN}/portal/setup`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: preCookie },
    body: new URLSearchParams({ token: TOKEN, name: "Owner", password: "correct horse battery", _csrf: csrfOf(await pre.text()) }),
    redirect: "manual",
  });
  expect(res.status).toBe(302);
  const cookie = `ot_session=${cookieValue(res, "ot_session")}`;
  const home = await SELF.fetch(`${ORIGIN}/portal`, { headers: { cookie } });
  const ownerId = (await env.DB.prepare("SELECT id FROM users WHERE role='owner'").first<{ id: string }>())!.id;
  return { cookie, csrf: csrfOf(await home.text()), ownerId };
}

const get = (s: Session, path: string) => SELF.fetch(ORIGIN + path, { headers: { cookie: s.cookie }, redirect: "manual" });

const post = (s: Session, path: string, fields: Record<string, string>) =>
  SELF.fetch(ORIGIN + path, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: s.cookie },
    body: new URLSearchParams({ _csrf: s.csrf, ...fields }),
    redirect: "manual",
  });

async function seedSong(chordpro: string, status: "published" | "review" | "hidden" = "published", createdBy = "seed") {
  return upsertSong(env.DB, { chordpro, source: "owner", status, createdBy });
}

beforeEach(async () => {
  await clearSongs();
});

describe("portal pages", () => {
  it("unauthenticated /portal redirects to login", async () => {
    const r = await SELF.fetch(`${ORIGIN}/portal`, { redirect: "manual" });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal/login");
    for (const p of ["/portal/review", "/portal/pair", "/portal/devices", "/portal/users", "/portal/transfer", "/portal/settings", "/portal/songs/new"]) {
      const r2 = await SELF.fetch(ORIGIN + p, { redirect: "manual" });
      expect(r2.status, p).toBe(302);
    }
  });

  it("after login /portal lists the seeded song titles", async () => {
    const s = await login();
    await seedSong(amazing);
    await seedSong("{title: Walkin' Home}\n{artist: Sample Writer}\n[G]Down the [C]lane", "review");
    const r = await get(s, "/portal");
    expect(r.status).toBe(200);
    const html = await r.text();
    expect(html).toContain("Amazing Grace");
    expect(html).toContain("Walkin&#39;");
    // status filter narrows the list
    const rev = await (await get(s, "/portal?status=review")).text();
    expect(rev).toContain("Walkin&#39;");
    expect(rev).not.toContain("Amazing Grace");
    // search uses FTS
    const q = await (await get(s, "/portal?q=newton")).text();
    expect(q).toContain("Amazing Grace");
    expect(q).not.toContain("Walkin&#39;");
  });

  it("posting the editor form with amazing-grace.cho creates a song and redirects to it", async () => {
    const s = await login();
    const r = await post(s, "/portal/songs/new", {
      chordpro: amazing, language: "en", categories: "hymn, classic", difficulty: "easy", status: "published",
    });
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT id, title, categories, difficulty, status, created_by FROM songs").first<any>();
    expect(row.title).toBe("Amazing Grace");
    expect(JSON.parse(row.categories)).toEqual(["hymn", "classic"]);
    expect(row.difficulty).toBe("easy");
    expect(row.created_by).toBe(s.ownerId);
    expect(r.headers.get("location")).toBe(`/portal/songs/${row.id}`);
    const page = await (await get(s, `/portal/songs/${row.id}`)).text();
    expect(page).toContain("Amazing Grace");
    // server-rendered preview: chords above lyrics
    expect(page).toContain('class="c">G7</span>');
  });

  it("editor update keeps the id and bumps the version; invalid chordpro re-renders with an error", async () => {
    const s = await login();
    const song = await seedSong(amazing);
    const r = await post(s, `/portal/songs/${song.id}`, {
      chordpro: amazing.replace("{key: G}", "{key: A}"), language: "en", categories: "", difficulty: "", status: "hidden",
    });
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT original_key, version, status FROM songs WHERE id = ?").bind(song.id).first<any>();
    expect(row).toEqual({ original_key: "A", version: 2, status: "hidden" });
    const bad = await post(s, "/portal/songs/new", { chordpro: "[G]no title here", status: "published" });
    expect(bad.status).toBe(422);
    expect(await bad.text()).toContain("no title here");
  });

  it("Developer device POST returns a one-time code for the signed-in account", async () => {
    const s = await login();
    const page = await (await get(s, "/portal/pair")).text();
    expect(page).toContain("Developer device");
    const r = await post(s, "/portal/pair", {});
    expect(r.status).toBe(200);
    const html = await r.text();
    expect(html).toMatch(/aria-label="Pairing code">\s*[A-Za-z0-9_-]{20,}/);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM pairing_codes WHERE user_id = ?").bind(s.ownerId).first<{ n: number }>();
    expect(n!.n).toBe(1);
  });

  it("revoke sets revoked_at", async () => {
    const s = await login();
    const m = await makeUser("user", "Mia");
    const dev = await env.DB.prepare("SELECT id FROM devices WHERE user_id = ?").bind(m.id).first<{ id: string }>();
    const list = await (await get(s, "/portal/devices")).text();
    expect(list).toContain("Mia");
    const r = await post(s, `/portal/devices/${dev!.id}/revoke`, {});
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT revoked_at FROM devices WHERE id = ?").bind(dev!.id).first<{ revoked_at: number | null }>();
    expect(row!.revoked_at).toBeGreaterThan(0);
  });

  it("publish on a review song sets status published", async () => {
    const s = await login();
    const song = await seedSong(amazing, "review");
    const page = await (await get(s, "/portal/review")).text();
    expect(page).toContain("Amazing Grace");
    const r = await post(s, `/portal/songs/${song.id}/status`, { status: "published" });
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT status, version FROM songs WHERE id = ?").bind(song.id).first<any>();
    expect(row).toEqual({ status: "published", version: 2 });
  });

  it("reports show on review and resolve sets resolved_at", async () => {
    const s = await login();
    const song = await seedSong(amazing);
    await env.DB.prepare("INSERT INTO reports (id, song_id, user_id, note, created_at, resolved_at) VALUES ('r1', ?, ?, 'wrong chord in verse 2', 1, NULL)")
      .bind(song.id, s.ownerId).run();
    expect(await (await get(s, "/portal/review")).text()).toContain("wrong chord in verse 2");
    const r = await post(s, "/portal/reports/r1/resolve", {});
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT resolved_at FROM reports WHERE id = 'r1'").first<any>();
    expect(row.resolved_at).toBeGreaterThan(0);
  });

  it("an admin publish/hide decision records moderated_at/by; a hidden reported song offers Publish", async () => {
    const s = await login();
    const song = await seedSong(amazing, "hidden");
    await env.DB.prepare("INSERT INTO reports (id, song_id, user_id, note, created_at, resolved_at) VALUES ('rh', ?, ?, 'not a song', 1, NULL)")
      .bind(song.id, s.ownerId).run();
    const page = await (await get(s, "/portal/review")).text();
    expect(page).toContain(`action="/portal/songs/${song.id}/status"`);
    expect(page).toMatch(/name="status" value="published"/);
    expect((await post(s, `/portal/songs/${song.id}/status`, { status: "published" })).status).toBe(302);
    const row = await env.DB.prepare("SELECT status, moderated_at, moderated_by FROM songs WHERE id = ?").bind(song.id).first<any>();
    expect(row.status).toBe("published");
    expect(row.moderated_by).toBe(s.ownerId);
    expect(row.moderated_at).toBeGreaterThan(0);
  });

  it("an admin editor save of an existing song pins it (moderated_at/by)", async () => {
    const s = await login();
    const song = await seedSong(amazing, "published", "someone");
    const r = await post(s, `/portal/songs/${song.id}`, {
      chordpro: amazing, language: "en", categories: "", difficulty: "", status: "hidden",
    });
    expect(r.status).toBe(302);
    const row = await env.DB.prepare("SELECT status, moderated_at, moderated_by FROM songs WHERE id = ?").bind(song.id).first<any>();
    expect(row.status).toBe("hidden");
    expect(row.moderated_by).toBe(s.ownerId);
    expect(row.moderated_at).toBeGreaterThan(0);
  });

  it("transfer: multipart import renders a summary; export and backup download", async () => {
    const s = await login();
    const fd = new FormData();
    fd.set("_csrf", s.csrf);
    fd.append("files", new File([amazing], "amazing-grace.cho"));
    const r = await SELF.fetch(`${ORIGIN}/portal/transfer`, {
      method: "POST", headers: { origin: ORIGIN, cookie: s.cookie }, body: fd, redirect: "manual",
    });
    expect(r.status).toBe(200);
    expect(await r.text()).toMatch(/1<\/b>\s*added/);
    const zip = await get(s, "/portal/export.zip");
    expect(zip.status).toBe(200);
    expect(zip.headers.get("content-type")).toBe("application/zip");
    const json = await get(s, "/portal/backup.json");
    expect(json.status).toBe(200);
    expect(await json.text()).toContain("Amazing Grace");
  });

  it("portal form posts need the session csrf and a same-origin Origin", async () => {
    const s = await login();
    const noToken = await SELF.fetch(`${ORIGIN}/portal/users`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie: s.cookie },
      body: new URLSearchParams({ name: "Eve" }),
    });
    expect(noToken.status).toBe(403);
    const crossSite = await SELF.fetch(`${ORIGIN}/portal/users`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: "https://evil.example", cookie: s.cookie },
      body: new URLSearchParams({ name: "Eve", _csrf: s.csrf }),
    });
    expect(crossSite.status).toBe(403);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE name = 'Eve'").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("plain portal forms are capped at 64 KB", async () => {
    const s = await login();
    const r = await post(s, "/portal/users", { name: "x".repeat(70_000) });
    expect(r.status).toBe(413);
  });

  it("portal pages carry a strict CSP with no inline script", async () => {
    const s = await login();
    const r = await get(s, "/portal");
    const csp = r.headers.get("content-security-policy")!;
    expect(csp).toContain("script-src 'self'");
    expect(csp).not.toContain("unsafe-inline");
    expect(r.headers.get("x-frame-options")).toBe("DENY");
    // no-referrer would make browsers send Origin: null on form posts and break every form
    expect(r.headers.get("referrer-policy")).toBe("same-origin");
    const css = await SELF.fetch(`${ORIGIN}/portal/assets/portal.css`);
    expect(css.status).toBe(200);
    expect(css.headers.get("content-type")).toContain("text/css");
    const js = await SELF.fetch(`${ORIGIN}/portal/assets/portal.js`);
    expect(js.status).toBe(200);
    expect(js.headers.get("content-type")).toContain("javascript");
  });
});

describe("portal session on JSON routes", () => {
  it("owner session + X-CSRF can call /v1 routes; without the header it cannot", async () => {
    const s = await login();
    const ok = await SELF.fetch(`${ORIGIN}/v1/convert`, {
      method: "POST",
      headers: { cookie: s.cookie, "x-csrf": s.csrf, "content-type": "application/json" },
      body: JSON.stringify({ text: "G       C\nAmazing grace", title: "AG" }),
    });
    expect(ok.status).toBe(200);
    expect((await ok.json<any>()).chordpro).toContain("{title: AG}");
    const noHeader = await SELF.fetch(`${ORIGIN}/v1/browse`, { headers: { cookie: s.cookie } });
    expect(noHeader.status).toBe(401);
    const wrong = await SELF.fetch(`${ORIGIN}/v1/browse`, { headers: { cookie: s.cookie, "x-csrf": "nope" } });
    expect(wrong.status).toBe(401);
  });

  it("a bearer header takes precedence over the session", async () => {
    const s = await login();
    const r = await SELF.fetch(`${ORIGIN}/v1/browse`, {
      headers: { cookie: s.cookie, "x-csrf": s.csrf, authorization: "Bearer not-a-real-token-xxxxxxxxxx" },
    });
    expect(r.status).toBe(401);
  });

  it("a member session never authenticates /v1", async () => {
    const m = await makeUser("user", "m");
    await env.DB.prepare("INSERT INTO sessions (id_hash, user_id, csrf, expires_at) VALUES (?, ?, 'c', ?)")
      .bind(await sha256Hex("member-session-id"), m.id, Date.now() + 60_000).run();
    const r = await SELF.fetch(`${ORIGIN}/v1/browse`, { headers: { cookie: "ot_session=member-session-id", "x-csrf": "c" } });
    expect(r.status).toBe(401);
  });
});

describe("bounded unauthenticated bodies", () => {
  it("POST /v1/pair over the cap is 413", async () => {
    const r = await SELF.fetch(`${ORIGIN}/v1/pair`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: "x", pad: "y".repeat(100_000) }),
    });
    expect(r.status).toBe(413);
  });

  it("login and setup form posts over 64 KB are 413 (with the pre-login csrf cookie)", async () => {
    for (const [path, page] of [["/portal/login", "/portal/login"], ["/portal/setup", `/portal/setup?token=${TOKEN}`]]) {
      const pre = await SELF.fetch(ORIGIN + page);
      const cookie = `ot_csrf=${cookieValue(pre, "ot_csrf")}`;
      const r = await SELF.fetch(ORIGIN + path, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN, cookie },
        body: new URLSearchParams({ token: TOKEN, name: "a", password: "b".repeat(70_000), _csrf: csrfOf(await pre.text()) }),
      });
      expect(r.status, path).toBe(413);
    }
  });

  it("a login post without the pre-login csrf cookie is 403 before the body is read", async () => {
    const r = await SELF.fetch(`${ORIGIN}/portal/login`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: ORIGIN },
      body: new URLSearchParams({ name: "a", password: "b".repeat(1_000_000) }),
    });
    expect(r.status).toBe(403);
  });

  it("an unauthenticated multipart POST to /portal/transfer is 403 without consuming the body", async () => {
    // A 1 MB chunk, then a stream that never ends: answering at all proves the body was not read.
    let pulls = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(ctrl) {
        pulls++;
        if (pulls === 1) ctrl.enqueue(new TextEncoder().encode("--b\r\n" + "x".repeat(1_000_000)));
        return new Promise(() => {});
      },
    });
    // Call the app directly: a real never-ending upload torn down after the response
    // surfaces as a runtime-level "network connection lost" unhandled rejection.
    const req = new Request(`${ORIGIN}/portal/transfer`, {
      method: "POST",
      headers: { "content-type": "multipart/form-data; boundary=b", origin: ORIGIN },
      body,
      // @ts-expect-error duplex is required for stream bodies
      duplex: "half",
    });
    const res = await Promise.race([
      app.fetch(req, env, { waitUntil() {}, passThroughOnException() {} } as any),
      new Promise<"timeout">((r) => setTimeout(() => r("timeout"), 3000)),
    ]);
    expect(res).not.toBe("timeout");
    expect((res as Response).status).toBe(403);
  });
});

describe("portal hardening", () => {
  const EVIL = `<script>alert(1)</script>"><img src=x onerror=alert(2)>`;

  it("user-supplied names and text render escaped on every page", async () => {
    const s = await login();
    const m = await makeUser("user", EVIL, { linked: true });
    await env.DB.prepare("UPDATE devices SET name = ? WHERE user_id = ?").bind(EVIL, m.id).run();
    await env.DB.prepare("UPDATE users SET email = ? WHERE id = ?").bind(EVIL, m.id).run();
    const song = await seedSong(`{title: ${EVIL}}\n{artist: ${EVIL}}\n[G]${EVIL}`, "review", m.id);
    await env.DB.prepare("INSERT INTO reports (id, song_id, user_id, note, created_at, resolved_at) VALUES ('rx', ?, ?, ?, 1, NULL)")
      .bind(song.id, m.id, EVIL).run();
    for (const p of ["/portal", "/portal/review", `/portal/songs/${song.id}`, "/portal/devices", "/portal/users"]) {
      const html = await (await get(s, p)).text();
      expect(html, p).not.toContain("<script>alert");
      expect(html, p).not.toContain("<img src=x");
      expect(html, p).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    }
  });

  it("a form POST with Origin: null is 403", async () => {
    const s = await login();
    const r = await SELF.fetch(`${ORIGIN}/portal/users`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: "null", cookie: s.cookie },
      body: new URLSearchParams({ name: "Eve", _csrf: s.csrf }),
    });
    expect(r.status).toBe(403);
  });

  it("a cross-site simple form POST with only the session cookie cannot reach /v1", async () => {
    const s = await login();
    const r = await SELF.fetch(`${ORIGIN}/v1/songs`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", origin: "https://evil.example", cookie: s.cookie },
      body: new URLSearchParams({ chordpro: "{title: Pwned}" }),
    });
    expect(r.status).toBe(401);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("portal errors render an HTML page with the portal headers", async () => {
    const s = await login();
    const nf = await get(s, "/portal/songs/does-not-exist");
    expect(nf.status).toBe(404);
    expect(nf.headers.get("content-type")).toContain("text/html");
    expect(nf.headers.get("content-security-policy")).toContain("script-src 'self'");
    expect(nf.headers.get("cache-control")).toBe("no-store");
    expect(await nf.text()).toContain('data-error="not_found"');
    const unknown = await get(s, "/portal/nope");
    expect(unknown.status).toBe(404);
    expect(await unknown.text()).toContain('data-error="not_found"');
    const big = await post(s, "/portal/users", { name: "x".repeat(70_000) });
    expect(big.status).toBe(413);
    expect(big.headers.get("content-type")).toContain("text/html");
    expect(await big.text()).toContain('data-error="too_large"');
    // JSON routes stay JSON
    const api = await SELF.fetch(`${ORIGIN}/v1/browse`);
    expect(api.headers.get("content-type")).toContain("application/json");
  });

  it("the developer device page explains pairing through POST /v1/pair", async () => {
    const s = await login();
    expect(await (await get(s, "/portal/pair")).text()).toContain("POST https://tuner.orbitapps.space/v1/pair");
  });
});

describe("browser preview parity", () => {
  type Norm = { type: string; label?: string; lines: unknown[] }[];
  const norm = (sections: any[]): Norm =>
    sections.map((s) => ({
      type: s.type,
      label: s.label,
      lines: s.lines.map((l: any) =>
        l.kind === "lyrics"
          ? { kind: l.kind, chords: l.segments.map((g: any) => g.chord ?? null), text: l.segments.map((g: any) => g.text).join("") }
          : { ...l },
      ),
    }));

  it("the client parser matches parse.ts on every fixture", () => {
    const client = (globalThis as any).OrbitTunerChordPro;
    expect(typeof client?.parse).toBe("function");
    for (const src of [amazing, unknownDir, unicode, noTitle, expectedPlain]) {
      const server = parseChordPro(src);
      const browser = client.parse(src);
      expect(browser.meta).toEqual(server.meta);
      expect(norm(browser.sections)).toEqual(norm(server.sections));
    }
  });
});

describe("import batching", () => {
  it("sends each .zip and .json alone and batches loose files by count and size", () => {
    const plan = (globalThis as any).OrbitTunerChordPro.importBatches as (
      f: { name: string; size: number }[], n: number, b: number,
    ) => { name: string }[][];
    const files = [
      { name: "a.cho", size: 10 }, { name: "backup.JSON", size: 10 }, { name: "b.cho", size: 10 },
      { name: "songs.zip", size: 10 }, { name: "c.cho", size: 10 }, { name: "d.cho", size: 91 },
    ];
    const names = plan(files, 2, 100).map((g) => g.map((f) => f.name));
    expect(names).toEqual([["backup.JSON"], ["songs.zip"], ["a.cho", "b.cho"], ["c.cho"], ["d.cho"]]);
  });
});

describe("sample songs", () => {
  it("POST /portal/seed loads six songs once and is idempotent", async () => {
    const s = await login();
    const r = await post(s, "/portal/seed", {});
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/portal?seeded=6&present=0&failed=0");
    const count = () => env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>().then((x) => x!.n);
    expect(await count()).toBe(6);
    const again = await post(s, "/portal/seed", {});
    expect(again.headers.get("location")).toBe("/portal?seeded=0&present=6&failed=0");
    expect(await count()).toBe(6);
  });

  it("needs the owner session and csrf", async () => {
    const anon = await SELF.fetch(`${ORIGIN}/portal/seed`, {
      method: "POST", headers: { origin: ORIGIN, "content-type": "application/x-www-form-urlencoded" }, body: "", redirect: "manual",
    });
    expect(anon.status).toBe(403);
    const s = await login();
    const noCsrf = await SELF.fetch(`${ORIGIN}/portal/seed`, {
      method: "POST", headers: { origin: ORIGIN, cookie: s.cookie, "content-type": "application/x-www-form-urlencoded" }, body: "", redirect: "manual",
    });
    expect(noCsrf.status).toBe(403);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM songs").first<{ n: number }>().then((x) => x!.n)).toBe(0);
  });

  it("the empty library and Settings offer the button; a populated library does not", async () => {
    const s = await login();
    expect(await (await get(s, "/portal")).text()).toContain('action="/portal/seed"');
    expect(await (await get(s, "/portal/settings")).text()).toContain('action="/portal/seed"');
    await post(s, "/portal/seed", {});
    expect(await (await get(s, "/portal?seeded=6&present=0&failed=0")).text()).toContain("6 added, 0 already present, 0 failed.");
    expect(await (await get(s, "/portal")).text()).not.toContain('action="/portal/seed"');
  });
});
