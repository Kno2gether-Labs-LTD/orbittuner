import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { dedupeKey, upsertSong } from "../src/songs/repo";
import { clearSongs, makeUser, ORIGIN } from "./helpers";
import amazing from "./fixtures/chordpro/amazing-grace.cho?raw";
import noTitle from "./fixtures/chordpro/no-title.cho?raw";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const hindi = "{title: Saath Chalo}\n{artist: Someone}\n[C]Chalo saath gaayen\n";
const rowRow = "{title: Row Row}\n[C]Row row row your boat\n";

async function get(path: string, headers: Record<string, string>) {
  return SELF.fetch(ORIGIN + path, { headers });
}

let owner: Awaited<ReturnType<typeof makeUser>>;
let member: Awaited<ReturnType<typeof makeUser>>;
let other: Awaited<ReturnType<typeof makeUser>>;

beforeEach(async () => {
  await clearSongs();
  await env.DB.batch([env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users")]);
  owner = await makeUser("owner", "o");
  member = await makeUser("user", "m");
  other = await makeUser("user", "x");
});

async function seed() {
  const ag = await upsertSong(env.DB, {
    chordpro: amazing, language: "en", categories: ["hymn", "devotional"],
    source: "owner", status: "published", createdBy: owner.id,
  });
  const hi = await upsertSong(env.DB, {
    chordpro: hindi, language: "hi", categories: ["devotional"],
    source: "owner", status: "published", createdBy: owner.id,
  });
  const rr = await upsertSong(env.DB, {
    chordpro: rowRow, categories: ["kids"], source: "web", status: "published", createdBy: owner.id,
  });
  return { ag, hi, rr };
}

describe("dedupeKey", () => {
  it("normalises diacritics, punctuation and case", () => {
    expect(dedupeKey("Zoë", "Hello!")).toBe(dedupeKey("zoe", "hello"));
    expect(dedupeKey(null, "Hello")).toBe("|hello");
  });
});

describe("upsertSong", () => {
  it("derives metadata from directives and versions on update", async () => {
    const s = await upsertSong(env.DB, {
      chordpro: amazing, source: "owner", status: "published", createdBy: owner.id,
    });
    expect(s).toMatchObject({
      title: "Amazing Grace", artist: "John Newton", originalKey: "G", tempoBpm: 80,
      timeSig: "3/4", capo: 0, version: 1, language: "en", categories: [], hasNative: false,
    });
    const s2 = await upsertSong(env.DB, {
      id: s.id, chordpro: amazing + "\n", source: "owner", status: "published", createdBy: owner.id,
    });
    expect(s2.version).toBe(2);
    const v = await env.DB.prepare("SELECT COUNT(*) n FROM song_versions WHERE song_id=?").bind(s.id).first<{ n: number }>();
    expect(v!.n).toBe(1);
    const f = await env.DB.prepare("SELECT COUNT(*) n FROM songs_fts WHERE song_id=?").bind(s.id).first<{ n: number }>();
    expect(f!.n).toBe(1);
  });
  it("rejects invalid chordpro", async () => {
    await expect(
      upsertSong(env.DB, { chordpro: noTitle, source: "owner", status: "published", createdBy: owner.id }),
    ).rejects.toThrow();
  });
});

describe("search", () => {
  it("finds by title and lyrics", async () => {
    const { ag } = await seed();
    let body = await (await get("/v1/search?q=amazing", owner.headers)).json<any>();
    expect(body.items[0].id).toBe(ag.id);
    expect(body.nextPage).toBeNull();
    body = await (await get("/v1/search?q=" + encodeURIComponent("how sweet the sound"), owner.headers)).json<any>();
    expect(body.items.map((i: any) => i.id)).toContain(ag.id);
    expect(body.items[0].chordpro).toBeUndefined();
  });
  it.each(["AC/DC", '"', "*", "-x", "(", "   ", '""'])("survives hostile query %j", async (q) => {
    await seed();
    const res = await get("/v1/search?q=" + encodeURIComponent(q), owner.headers);
    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(Array.isArray(body.items)).toBe(true);
    expect(body).toHaveProperty("nextPage");
    if (q.trim() === "" || /^[^\p{L}\p{N}]+$/u.test(q)) expect(body.items).toEqual([]);
  });
  it("filters by lang and cat", async () => {
    const { hi, ag } = await seed();
    let body = await (await get("/v1/search?q=chalo&lang=hi", owner.headers)).json<any>();
    expect(body.items.map((i: any) => i.id)).toEqual([hi.id]);
    body = await (await get("/v1/search?q=amazing&lang=hi", owner.headers)).json<any>();
    expect(body.items).toEqual([]);
    body = await (await get("/v1/search?q=grace&cat=devotional", owner.headers)).json<any>();
    expect(body.items.map((i: any) => i.id)).toEqual([ag.id]);
    body = await (await get("/v1/search?q=grace&cat=kids", owner.headers)).json<any>();
    expect(body.items).toEqual([]);
  });
  it("requires a device token", async () => {
    expect((await SELF.fetch(ORIGIN + "/v1/search?q=a")).status).toBe(401);
  });
});

describe("visibility", () => {
  it("members only see their own review songs", async () => {
    await seed();
    const mine = await upsertSong(env.DB, {
      chordpro: "{title: Secret Mine}\n[C]hidden words\n", source: "web", status: "review", createdBy: member.id,
    });
    const theirs = await upsertSong(env.DB, {
      chordpro: "{title: Secret Theirs}\n[C]hidden words\n", source: "web", status: "review", createdBy: other.id,
    });
    const ids = async (path: string) =>
      (await (await get(path, member.headers)).json<any>()).items.map((i: any) => i.id);
    expect(await ids("/v1/search?q=secret")).toEqual([mine.id]);
    expect(await ids("/v1/browse")).toContain(mine.id);
    expect(await ids("/v1/browse")).not.toContain(theirs.id);
    expect((await get(`/v1/songs/${mine.id}`, member.headers)).status).toBe(200);
    const r = await get(`/v1/songs/${theirs.id}`, member.headers);
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: "not_found" });
    const ch = await (await get(`/v1/songs/changes?since=0&ids=${mine.id},${theirs.id}`, member.headers)).json<any>();
    expect(ch.items.map((i: any) => i.id)).toEqual([mine.id]);
    const own = await ids("/v1/browse");
    expect(own).not.toContain(theirs.id);
    expect((await get(`/v1/songs/${theirs.id}`, owner.headers)).status).toBe(200);
    const f = await (await get("/v1/facets", member.headers)).json<any>();
    expect(f.languages).toEqual([{ code: "en", count: 3 }, { code: "hi", count: 1 }]);
  });
});

describe("browse, facets, get, changes", () => {
  it("browse is most-recent first", async () => {
    const { ag, hi, rr } = await seed();
    const body = await (await get("/v1/browse", owner.headers)).json<any>();
    expect(body.items.map((i: any) => i.id)).toEqual([rr.id, hi.id, ag.id]);
    expect(body.items).toHaveLength(3);
    const upd = await (await get("/v1/browse?lang=hi", owner.headers)).json<any>();
    expect(upd.items.map((i: any) => i.id)).toEqual([hi.id]);
  });
  it("facets match seeded data", async () => {
    await seed();
    const f = await (await get("/v1/facets", owner.headers)).json<any>();
    expect(f.languages).toEqual([{ code: "en", count: 2 }, { code: "hi", count: 1 }]);
    expect(f.categories).toEqual([
      { name: "devotional", count: 2 }, { name: "hymn", count: 1 }, { name: "kids", count: 1 },
    ]);
  });
  it("get returns a full Song, 404 for unknown", async () => {
    const { ag } = await seed();
    const s = await (await get(`/v1/songs/${ag.id}`, owner.headers)).json<any>();
    expect(s.chordpro).toContain("Amazing");
    expect(s.status).toBe("published");
    expect((await get("/v1/songs/nope", owner.headers)).status).toBe(404);
  });
  it("changes returns only ids updated after since", async () => {
    const { ag, hi } = await seed();
    await sleep(5);
    const since = Date.now();
    await sleep(5);
    await upsertSong(env.DB, {
      id: ag.id, chordpro: amazing + "\n", categories: ["hymn"], source: "owner", status: "published", createdBy: owner.id,
    });
    const body = await (await get(`/v1/songs/changes?since=${since}&ids=${ag.id},${hi.id}`, owner.headers)).json<any>();
    expect(body.items.map((i: any) => i.id)).toEqual([ag.id]);
    expect(body.items[0].version).toBe(2);
    const bad = await get("/v1/songs/changes?since=0&ids=" + Array.from({ length: 201 }, (_, i) => "a" + i).join(","), owner.headers);
    expect(bad.status).toBe(400);
  });

  it("changes handles 150 ids and duplicate ids (D1 100-param cap not provable locally)", async () => {
    const { ag } = await seed();
    const ids = [ag.id, ag.id, ...Array.from({ length: 148 }, (_, i) => `missing${i}`)];
    const res = await get(`/v1/songs/changes?since=0&ids=${ids.join(",")}`, member.headers);
    expect(res.status).toBe(200);
    expect((await res.json<any>()).items.map((i: any) => i.id)).toEqual([ag.id]);
  });
  it("paginates 25 per page", async () => {
    for (let i = 0; i < 30; i++) {
      await upsertSong(env.DB, {
        chordpro: `{title: Song ${i}}\n[C]la la\n`, source: "owner", status: "published", createdBy: owner.id,
      });
    }
    const p0 = await (await get("/v1/browse", owner.headers)).json<any>();
    expect(p0.items).toHaveLength(25);
    expect(p0.nextPage).toBe(1);
    const p1 = await (await get("/v1/browse?page=1", owner.headers)).json<any>();
    expect(p1.items).toHaveLength(5);
    expect(p1.nextPage).toBeNull();
    const s0 = await (await get("/v1/search?q=song", owner.headers)).json<any>();
    expect(s0.items).toHaveLength(25);
    expect(s0.nextPage).toBe(1);
  });
});

describe("updates", () => {
  it("keeps omitted fields, dedupes categories, stores previous chordpro, reindexes", async () => {
    const s = await upsertSong(env.DB, {
      chordpro: "{title: Old}\n[C]zebra lyrics\n", language: "hi", categories: ["a", " a ", "b", ""],
      difficulty: "easy", sourceUrl: "https://x.test/1", source: "web", status: "published", createdBy: owner.id,
    });
    expect(s.categories).toEqual(["a", "b"]);
    const next = "{title: Old}\n[C]giraffe lyrics\n";
    const s2 = await upsertSong(env.DB, {
      id: s.id, chordpro: next, source: "web", status: "published", createdBy: owner.id,
    });
    expect(s2).toMatchObject({ language: "hi", categories: ["a", "b"], difficulty: "easy", sourceUrl: "https://x.test/1", version: 2 });
    const v = await env.DB.prepare("SELECT chordpro FROM song_versions WHERE song_id=?").bind(s.id).all<{ chordpro: string }>();
    expect(v.results.map((r) => r.chordpro)).toEqual(["{title: Old}\n[C]zebra lyrics\n"]);
    const find = async (q: string) =>
      (await (await get("/v1/search?q=" + q, owner.headers)).json<any>()).items.length;
    expect(await find("giraffe")).toBe(1);
    expect(await find("zebra")).toBe(0);
  });
  it("members see own hidden song via get, not another member's", async () => {
    const mine = await upsertSong(env.DB, {
      chordpro: "{title: H1}\n[C]a\n", source: "web", status: "hidden", createdBy: member.id,
    });
    const theirs = await upsertSong(env.DB, {
      chordpro: "{title: H2}\n[C]a\n", source: "web", status: "hidden", createdBy: other.id,
    });
    expect((await get(`/v1/songs/${mine.id}`, member.headers)).status).toBe(200);
    expect((await get(`/v1/songs/${theirs.id}`, member.headers)).status).toBe(404);
  });
});
