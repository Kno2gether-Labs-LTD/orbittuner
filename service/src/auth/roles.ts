export type Role = "owner" | "admin" | "user";

/** Owner and admins see every song and moderate; only the owner manages roles, keys and settings. */
export function isAdmin(role: Role | null | undefined): boolean {
  return role === "owner" || role === "admin";
}

/** Normalise a stored role: anything that is not owner/admin is treated as a plain user. */
export function asRole(role: string | null | undefined): Role {
  return role === "owner" || role === "admin" ? role : "user";
}
