import type { Context } from "hono";
import type { AppEnv } from "../auth/devices";

/** Same-origin only: external stylesheet and script, no inline script or style attributes. */
export const PORTAL_CSP = [
  "default-src 'none'",
  "style-src 'self'",
  "script-src 'self'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "form-action 'self'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
].join("; ");

export function securityHeaders(c: Context<AppEnv>) {
  c.header("Cache-Control", "no-store");
  c.header("X-Frame-Options", "DENY");
  c.header("X-Content-Type-Options", "nosniff");
  // Not "no-referrer": under it browsers send `Origin: null` on form posts, which the CSRF
  // guard (rightly) rejects. "same-origin" keeps the Origin for our own forms and sends
  // nothing to other sites.
  c.header("Referrer-Policy", "same-origin");
  c.header("Content-Security-Policy", PORTAL_CSP);
}

type Renderable = { toString(): string | Promise<string> };
export type PageStatus = 200 | 400 | 401 | 403 | 404 | 409 | 413 | 422 | 429 | 500 | 503;

/** Render a JSX page with the portal security headers. */
export async function render(c: Context<AppEnv>, node: Renderable | null | undefined, status: PageStatus = 200) {
  securityHeaders(c);
  return c.html("<!doctype html>" + (node ? await node.toString() : ""), status);
}
