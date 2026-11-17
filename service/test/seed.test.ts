import { env, SELF } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { clearSongs, makeUser, ORIGIN } from "./helpers";
import { SEED_SONGS, seedSongs } from "../src/seed";
import { validateSong } from "../src/chordpro/parse";

let owner: Awaited<ReturnType<typeof makeUser>>;

beforeEach(async () => {
  await clearSongs();
  await env.DB.batch([env.DB.prepare("DELETE FROM devices"), env.DB.prepare("DELETE FROM users")]);
  owner = await makeUser("owner", "o");
});

const ow = () => ({ id: owner.id, name: owner.name, role: "owner" as const, linked: false });

describe("seed songs", () => {
  it("adds six songs, then reports six duplicates", async () => {
    const first = await seedSongs(env.DB, ow());
    expect(first).toEqual({ added: 6, duplicates: [], failed: [] });
    const second = await seedSongs(env.DB, ow());
    expect(second.added).toBe(0);
    expect(second.duplicates).toHaveLength(6);
    expect(second.failed).toEqual([]);
  });

  it("every seed song is valid ChordPro with key, tempo and time", () => {
    expect(SEED_SONGS).toHaveLength(6);
    for (const s of SEED_SONGS) {
      const v = validateSong(s.text);
      expect(v.ok, s.file).toBe(true);
      for (const d of ["{key:", "{tempo:", "{time:", "{artist:"]) expect(s.text, s.file).toContain(d);
    }
  });

  it("stores language and categories from the manifest", async () => {
    await seedSongs(env.DB, ow());
    const r = await env.DB.prepare("SELECT language, categories, status FROM songs WHERE title = 'Amazing Grace'")
      .first<{ language: string; categories: string; status: string }>();
    expect(r?.language).toBe("en");
    expect(JSON.parse(r!.categories)).toEqual(["hymn"]);
    expect(r?.status).toBe("published");
  });
});
