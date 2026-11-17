import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

describe("health", () => {
  it("GET /health returns ok", async () => {
    const res = await SELF.fetch("https://tuner.orbitapps.space/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });

  it("unknown route returns not_found JSON", async () => {
    const res = await SELF.fetch("https://tuner.orbitapps.space/nope");
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not_found" });
  });

  it("schema is migrated with seeded settings", async () => {
    const { env } = await import("cloudflare:test");
    const row = await env.DB.prepare(
      "SELECT value FROM settings WHERE key='discover_daily_limit'",
    ).first<{ value: string }>();
    expect(row?.value).toBe("20");
    const t = await env.DB.prepare(
      "SELECT name FROM sqlite_master WHERE name IN ('songs_fts','users','devices','pairing_codes','sessions','songs','song_versions','favourites','playlists','playlist_items','reports','settings')",
    ).all();
    expect(t.results.length).toBe(12);
  });
});
