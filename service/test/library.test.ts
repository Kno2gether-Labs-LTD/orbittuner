import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { makeUser, ORIGIN } from "./helpers";
import { ulid } from "../src/lib/ulid";

type U = Awaited<ReturnType<typeof makeUser>>;
let a: U;
let b: U;

beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users"),
    env.DB.prepare("DELETE FROM favourites"), env.DB.prepare("DELETE FROM playlists"),
    env.DB.prepare("DELETE FROM playlist_items"),
  ]);
  a = await makeUser("user", "a");
  b = await makeUser("user", "b");
});

function call(method: string, path: string, who: U, body?: unknown, raw = false) {
  return SELF.fetch(ORIGIN + path, {
    method,
    headers: { ...who.headers, "content-type": "application/json" },
    body: body === undefined ? undefined : raw ? (body as string) : JSON.stringify(body),
  });
}
const item = (songId: string, position: string, settings: object = {}) => ({ songId, position, settings });
const pl = (name: string, updatedAt: number, items: unknown[] = [], deleted = false) => ({ name, deleted, updatedAt, items });

describe("favourites", () => {
  it("newer updatedAt wins", async () => {
    let r = await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "A", favourite: true, updatedAt: 10 }] });
    expect(r.status).toBe(200);
    r = await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "A", favourite: false, updatedAt: 5 }] });
    expect(((await r.json()) as any).items).toEqual([{ songId: "A", favourite: true, updatedAt: 10 }]);
    r = await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "A", favourite: false, updatedAt: 11 }] });
    expect(((await r.json()) as any).items[0].favourite).toBe(false);
    const g = await call("GET", "/v1/me/favourites", a);
    expect(((await g.json()) as any).items).toEqual([{ songId: "A", favourite: false, updatedAt: 11 }]);
  });
  it("is per user and accepts unknown songs", async () => {
    await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "nope", favourite: true, updatedAt: 1 }] });
    const g = await call("GET", "/v1/me/favourites", b);
    expect(((await g.json()) as any).items).toEqual([]);
  });
  it("validates", async () => {
    for (const bad of [
      { items: [{ songId: "A", favourite: "yes", updatedAt: 1 }] },
      { items: [{ songId: "A", favourite: true, updatedAt: -1 }] },
      { items: [{ songId: "A", favourite: true, updatedAt: 1.5 }] },
      { items: [{ songId: "", favourite: true, updatedAt: 1 }] },
      { items: "x" },
      { items: Array.from({ length: 1001 }, (_, i) => ({ songId: "s" + i, favourite: true, updatedAt: 1 })) },
    ]) {
      expect((await call("PUT", "/v1/me/favourites", a, bad)).status).toBe(400);
    }
  });
  it("handles 1000 items", async () => {
    const items = Array.from({ length: 1000 }, (_, i) => ({ songId: "s" + i, favourite: true, updatedAt: 1 }));
    const r = await call("PUT", "/v1/me/favourites", a, { items });
    expect(r.status).toBe(200);
    expect(((await r.json()) as any).items.length).toBe(1000);
  });
  it("equal timestamp keeps stored; duplicate songId in one PUT keeps newest", async () => {
    await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "A", favourite: true, updatedAt: 10 }] });
    let r = await call("PUT", "/v1/me/favourites", a, { items: [{ songId: "A", favourite: false, updatedAt: 10 }] });
    expect(((await r.json()) as any).items[0].favourite).toBe(true);
    r = await call("PUT", "/v1/me/favourites", a, { items: [
      { songId: "B", favourite: true, updatedAt: 30 }, { songId: "B", favourite: false, updatedAt: 20 },
      { songId: "C", favourite: false, updatedAt: 5 }, { songId: "C", favourite: true, updatedAt: 6 },
    ] });
    const items = ((await r.json()) as any).items;
    expect(items.find((i: any) => i.songId === "B")).toEqual({ songId: "B", favourite: true, updatedAt: 30 });
    expect(items.find((i: any) => i.songId === "C")).toEqual({ songId: "C", favourite: true, updatedAt: 6 });
  });
  it("413 on huge body", async () => {
    const r = await call("PUT", "/v1/me/favourites", a, JSON.stringify({ pad: "x".repeat(600_000) }), true);
    expect(r.status).toBe(413);
  });
});

describe("playlists", () => {
  it("sorts items by position bytewise and normalises settings", async () => {
    const id = ulid();
    const r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Sunday", 100, [
      item("s1", "a1"), item("s2", "a0V", { transpose: 2, junk: 1 }), item("s3", "a0", { capo: 3, scrollSpeed: 12.5 }),
    ]));
    expect(r.status).toBe(200);
    const p = (await r.json()) as any;
    expect(p.id).toBe(id);
    expect(p.items.map((i: any) => i.position)).toEqual(["a0", "a0V", "a1"]);
    expect(p.items[1].settings).toEqual({ transpose: 2 });
    const g = await call("GET", "/v1/me/playlists", a);
    const list = ((await g.json()) as any).items;
    expect(list[0].items.map((i: any) => i.position)).toEqual(["a0", "a0V", "a1"]);
    expect(list[0].deleted).toBe(false);
  });
  it("409 stale with current", async () => {
    const id = ulid();
    await call("PUT", `/v1/me/playlists/${id}`, a, pl("One", 100, [item("s1", "a0")]));
    let r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Two", 50));
    expect(r.status).toBe(409);
    let j = (await r.json()) as any;
    expect(j.error).toBe("stale");
    expect(j.current.updatedAt).toBe(100);
    expect(j.current.name).toBe("One");
    r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Same", 100));
    expect(r.status).toBe(409);
    r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Three", 101, [item("s9", "b")]));
    expect(r.status).toBe(200);
    j = await r.json();
    expect(j.items.length).toBe(1);
    expect(j.items[0].songId).toBe("s9");
  });
  it("stale PUT leaves items untouched", async () => {
    const id = ulid();
    await call("PUT", `/v1/me/playlists/${id}`, a, pl("One", 100, [item("s1", "a0", { capo: 2 })]));
    const r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Two", 50, [item("zz", "b")]));
    expect(r.status).toBe(409);
    const g = ((await (await call("GET", "/v1/me/playlists", a)).json()) as any).items[0];
    expect(g.name).toBe("One");
    expect(g.items).toEqual([{ songId: "s1", position: "a0", settings: { capo: 2 } }]);
  });
  it("tombstone PUT round-trips with items", async () => {
    const id = ulid();
    const r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Bye", 10, [item("s1", "a0"), item("s2", "a1")], true));
    const p = (await r.json()) as any;
    expect(p.deleted).toBe(true);
    expect(p.items.length).toBe(2);
    const g = ((await (await call("GET", "/v1/me/playlists?since=0", a)).json()) as any).items[0];
    expect(g.deleted).toBe(true);
    expect(g.items.length).toBe(2);
  });
  it("500-item playlist round-trips", async () => {
    const id = ulid();
    const items = Array.from({ length: 500 }, (_, i) => item("s" + i, "p" + String(i).padStart(4, "0"), { transpose: i % 11 }));
    const r = await call("PUT", `/v1/me/playlists/${id}`, a, pl("Big", 10, items));
    expect(r.status).toBe(200);
    const p = (await r.json()) as any;
    expect(p.items.length).toBe(500);
    expect(p.items[499]).toEqual(items[499]);
  });
  it("tombstones appear in since and respect the cut-off", async () => {
    const id = ulid();
    await call("PUT", `/v1/me/playlists/${id}`, a, pl("Gone", 200, [], true));
    const first = (await (await call("GET", "/v1/me/playlists?since=0", a)).json()) as any;
    expect(first.items.length).toBe(1);
    expect(first.items[0].deleted).toBe(true);
    const g = (await (await call("GET", `/v1/me/playlists?since=${first.cursor}`, a)).json()) as any;
    expect(g.items).toEqual([]);
    expect((await call("GET", "/v1/me/playlists?since=abc", a)).status).toBe(400);
  });
  it("cursor tracks server modified_at, not client updatedAt", async () => {
    const id1 = ulid(), id2 = ulid();
    await call("PUT", `/v1/me/playlists/${id1}`, a, pl("First", 5000));
    const g1 = (await (await call("GET", "/v1/me/playlists?since=0", a)).json()) as any;
    expect(g1.items.length).toBe(1);
    expect(typeof g1.cursor).toBe("number");
    expect(g1.items[0].modifiedAt).toBeGreaterThan(5000);
    const c = g1.cursor;
    expect(c).toBe(g1.items[0].modifiedAt);
    await call("PUT", `/v1/me/playlists/${id2}`, a, pl("Skewed", 1000));
    const g2 = (await (await call("GET", `/v1/me/playlists?since=${c}`, a)).json()) as any;
    expect(g2.items.map((p: any) => p.id)).toEqual([id2]);
    expect(g2.items[0].updatedAt).toBe(1000);
    expect(g2.cursor).toBeGreaterThan(c);
    const g3 = (await (await call("GET", `/v1/me/playlists?since=${g2.cursor}`, a)).json()) as any;
    expect(g3.items).toEqual([]);
    expect(g3.cursor).toBe(g2.cursor);
    const g4 = (await (await call("GET", "/v1/me/playlists?since=99999999999999", a)).json()) as any;
    expect(g4.cursor).toBe(99999999999999);
  });
  it("modifiedAt never goes backwards and tombstones carry it", async () => {
    const id = ulid();
    const r1 = (await (await call("PUT", `/v1/me/playlists/${id}`, a, pl("x", 10))).json()) as any;
    const r2 = (await (await call("PUT", `/v1/me/playlists/${id}`, a, pl("x", 11, [], true))).json()) as any;
    expect(r2.modifiedAt).toBeGreaterThan(r1.modifiedAt);
    const g = (await (await call("GET", `/v1/me/playlists?since=${r1.modifiedAt}`, a)).json()) as any;
    expect(g.items[0].deleted).toBe(true);
    expect(g.items[0].modifiedAt).toBe(r2.modifiedAt);
    const st = (await (await call("PUT", `/v1/me/playlists/${id}`, a, pl("x", 5))).json()) as any;
    expect(st.current.modifiedAt).toBe(r2.modifiedAt);
  });
  it("isolates users", async () => {
    const id = ulid();
    await call("PUT", `/v1/me/playlists/${id}`, a, pl("Mine", 100));
    expect(((await (await call("GET", "/v1/me/playlists", b)).json()) as any).items).toEqual([]);
    expect((await call("PUT", `/v1/me/playlists/${id}`, b, pl("Steal", 999))).status).toBe(404);
    const g = ((await (await call("GET", "/v1/me/playlists", a)).json()) as any).items;
    expect(g[0].name).toBe("Mine");
  });
  it("validates", async () => {
    const id = ulid();
    const many = Array.from({ length: 501 }, (_, i) => item("s" + i, "a"));
    for (const [path, body] of [
      ["/v1/me/playlists/not-a-ulid", pl("x", 1)],
      [`/v1/me/playlists/${id}`, pl("", 1)],
      [`/v1/me/playlists/${id}`, pl("x".repeat(201), 1)],
      [`/v1/me/playlists/${id}`, pl("x", -1)],
      [`/v1/me/playlists/${id}`, pl("x", 1, many)],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "")])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a-b")])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a".repeat(65))])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a", { transpose: 12 })])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a", { capo: -1 })])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a", { transpose: 1.5 })])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a", { scrollSpeed: 1001 })])],
      [`/v1/me/playlists/${id}`, pl("x", 1, [item("s", "a"), item("s", "b")])],
      [`/v1/me/playlists/${id}`, { ...pl("x", 1), deleted: "no" }],
    ] as [string, unknown][]) {
      expect((await call("PUT", path, a, body)).status, path + JSON.stringify(body).slice(0, 80)).toBe(400);
    }
    expect((await call("PUT", `/v1/me/playlists/${id}`, a, pl("x", 1, many.slice(0, 500).map((x, i) => item("s" + i, "a" + i))))).status).toBe(200);
  });
  it("413 on huge body", async () => {
    const r = await call("PUT", `/v1/me/playlists/${ulid()}`, a, JSON.stringify({ pad: "x".repeat(600_000) }), true);
    expect(r.status).toBe(413);
  });
});
