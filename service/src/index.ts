import { Hono } from "hono";
import type { Env } from "./env";
import { ApiError } from "./lib/errors";
import { devicesApp, requireDevice, type AppEnv } from "./auth/devices";
import { portalApp } from "./auth/portal";
import { accountsApp } from "./auth/accounts";
import { googleApp } from "./auth/google";
import { portalPages } from "./portal/routes";
import { portalError, wantsPortalHtml } from "./portal/ErrorPage";
import { songsApp } from "./songs/routes";
import { libraryApp } from "./library/routes";
import { transferApp } from "./transfer/routes";
import { voiceApp } from "./voice/routes";
import { handleImportBatch } from "./ai/jobs";

const app = new Hono<AppEnv>();

app.onError((err, c) => {
  if (wantsPortalHtml(c)) {
    if (!(err instanceof ApiError)) console.error(err);
    return err instanceof ApiError ? portalError(c, err.status, err.code) : portalError(c, 500, "internal");
  }
  if (err instanceof ApiError) {
    return c.json({ ...err.details, error: err.code }, err.status as 400);
  }
  console.error(err);
  return c.json({ error: "internal" }, 500);
});

app.notFound((c) => (wantsPortalHtml(c) ? portalError(c, 404, "not_found") : c.json({ error: "not_found" }, 404)));

app.get("/health", (c) => c.json({ ok: true }));

// Every /v1 route requires a device token except pairing, anonymous sign-up and Google sign-in
// sent without an Authorization header (a new device). Google sign-in WITH a header is
// authenticated like any other route, so a bad token is 401, never a silent tokenless sign-in.
// Mounted before any route (and before notFound) so unknown /v1 paths answer 401, not 404.
const OPEN_POSTS = new Set(["/v1/pair", "/v1/accounts/anonymous"]);
app.use("/v1/*", async (c, next) => {
  if (c.req.method === "POST" && OPEN_POSTS.has(c.req.path)) return next();
  if (c.req.method === "POST" && c.req.path === "/v1/accounts/google" && c.req.header("authorization") === undefined) {
    return next();
  }
  await requireDevice(c, next);
});
app.route("/", devicesApp);
app.route("/", accountsApp);
app.route("/", googleApp);
app.route("/", portalApp);
app.route("/", portalPages);
app.route("/", songsApp);
app.route("/", libraryApp);
app.route("/", transferApp);
app.route("/", voiceApp);

export { app };

export default {
  fetch: app.fetch,
  queue: (batch, env) => handleImportBatch(batch, env),
} satisfies ExportedHandler<Env, unknown>;
