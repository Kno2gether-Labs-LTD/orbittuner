import { env } from "cloudflare:test";
import { randomToken, sha256Hex } from "../src/lib/crypto";
import { ulid } from "../src/lib/ulid";

export const ORIGIN = "https://tuner.orbitapps.space";

/** Insert a user plus a paired device and return the bearer token. `linked` sets a Google sub. */
export async function makeUser(role: "owner" | "admin" | "user", name: string = role, opts: { linked?: boolean } = {}) {
  const id = ulid();
  const token = randomToken(32);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO users (id, name, role, password_hash, created_at, google_sub) VALUES (?, ?, ?, NULL, ?, ?)")
      .bind(id, name, role, now, opts.linked ? `g-${id}` : null),
    env.DB.prepare(
      "INSERT INTO devices (id, user_id, name, token_hash, revoked_at, last_seen_at, created_at) VALUES (?, ?, 'test', ?, NULL, ?, ?)",
    ).bind(ulid(), id, await sha256Hex(token), now, now),
  ]);
  return { id, name, role, token, headers: { authorization: `Bearer ${token}` } };
}

export async function clearSongs() {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM songs"),
    env.DB.prepare("DELETE FROM song_versions"),
    env.DB.prepare("DELETE FROM songs_fts"),
  ]);
}
