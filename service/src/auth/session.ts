import type { Context } from "hono";
import type { Env } from "../env";
import { getCookie } from "hono/cookie";
import { sha256Hex } from "../lib/crypto";
import type { Role } from "./roles";

export const SESSION_COOKIE = "ot_session";

export type SessionRow = {
  id_hash: string;
  csrf: string;
  user_id: string;
  name: string;
  role: Role;
  disabled_at: number | null;
};

/** The live session named by the session cookie, joined to its user; null when absent or expired. */
export async function loadSession<E extends { Bindings: Env }>(c: Context<E>): Promise<SessionRow | null> {
  const id = getCookie(c, SESSION_COOKIE);
  if (!id || id.length > 200) return null;
  return c.env.DB.prepare(
    `SELECT s.id_hash, s.csrf, u.id AS user_id, u.name, u.role, u.disabled_at
       FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.id_hash = ? AND s.expires_at > ?`,
  )
    .bind(await sha256Hex(id), Date.now())
    .first<SessionRow>();
}
