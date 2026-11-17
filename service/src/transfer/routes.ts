import { Hono } from "hono";
import type { AppEnv } from "../auth/devices";
import { ApiError } from "../lib/errors";
import { readFormDataBounded, readJsonBody } from "../lib/body";
import { createLinkJob, getJob, parseUrls } from "../ai/jobs";
import { discover, parseDiscoverBody } from "../ai/discover";
import { clientIpKey } from "../lib/ip";
import { exportJson, exportZip, importFiles, MAX_ENTRIES, MAX_TOTAL_BYTES, TooManySongsError } from "./zip";

const MAX_EXPORT_IDS = 1000;

export const transferApp = new Hono<AppEnv>();

function zipResponse(bytes: Uint8Array, filename: string): Response {
  return new Response(bytes, {
    headers: {
      "content-type": "application/zip",
      "content-disposition": `attachment; filename="${filename}"`,
      "cache-control": "no-store",
    },
  });
}

transferApp.get("/v1/export", async (c) => {
  const raw = c.req.query("ids");
  let ids: string[] | undefined;
  if (raw !== undefined) {
    ids = [...new Set(raw.split(",").map((s) => s.trim()).filter(Boolean))];
    if (ids.length > MAX_EXPORT_IDS) throw new ApiError(400, "too_many_ids");
  }
  return zipResponse(await exportZip(c.env.DB, c.var.user, ids), "orbittuner-songs.zip");
});

transferApp.get("/v1/export/json", async (c) => {
  if (c.var.user.role !== "owner") throw new ApiError(403, "forbidden");
  return new Response(await exportJson(c.env.DB), {
    headers: {
      "content-type": "application/json",
      "content-disposition": 'attachment; filename="orbittuner-backup.json"',
      "cache-control": "no-store",
    },
  });
});

transferApp.get("/v1/playlists/:id/export", async (c) => {
  const db = c.env.DB;
  const pl = await db
    .prepare("SELECT id FROM playlists WHERE id = ? AND user_id = ? AND deleted = 0")
    .bind(c.req.param("id"), c.var.user.id)
    .first<{ id: string }>();
  if (!pl) throw new ApiError(404, "not_found");
  const { results } = await db
    .prepare("SELECT song_id FROM playlist_items WHERE playlist_id = ? ORDER BY position, song_id")
    .bind(pl.id)
    .all<{ song_id: string }>();
  return zipResponse(await exportZip(db, c.var.user, results.map((r) => r.song_id)), "orbittuner-playlist.zip");
});

// multipart/form-data, any number of file fields; capped by body size and zip limits.
transferApp.post("/v1/import", async (c) => {
  const ct = c.req.header("content-type") ?? "";
  if (!/^multipart\/form-data\s*;/i.test(ct)) throw new ApiError(400, "invalid_input");
  const form = await readFormDataBounded(c, MAX_TOTAL_BYTES);
  const files: { name: string; data: Uint8Array }[] = [];
  // Optional text field ai=auto|on|off: AI conversion for .txt files (default off).
  const aiField = form.get("ai");
  let ai: "auto" | "on" | "off" = "off";
  if (aiField !== null) {
    if (aiField !== "auto" && aiField !== "on" && aiField !== "off") throw new ApiError(400, "invalid_input");
    ai = aiField;
  }
  for (const [, v] of form.entries()) {
    if (typeof v === "string") continue;
    if (files.length >= MAX_ENTRIES) throw new ApiError(413, "too_large");
    files.push({ name: v.name || "upload", data: new Uint8Array(await v.arrayBuffer()) });
  }
  if (files.length === 0) throw new ApiError(400, "invalid_input");
  let result;
  try {
    result = await importFiles(c.env.DB, c.var.user, files, { ai, env: c.env });
  } catch (e) {
    if (e instanceof TooManySongsError) return c.json({ error: e.code, max: e.max }, 413);
    throw e;
  }
  if (result.failed.some((f) => f.error === "archive_too_large")) {
    return c.json({ error: "too_large", ...result }, 413);
  }
  return c.json(result);
});

// Bulk link import: one queue message per URL; poll GET /v1/import/jobs/:id for progress.
transferApp.post("/v1/import/links", async (c) => {
  const b = await readJsonBody(c);
  const jobId = await createLinkJob(c.env, c.var.user, parseUrls(b.urls));
  return c.json({ jobId }, 202);
});

// Find on the web: search "<q> chords", queue the top candidate pages as a discover job.
transferApp.post("/v1/discover", async (c) => {
  const { q, lang } = parseDiscoverBody(await readJsonBody(c, 4096));
  let waitUntil: ((p: Promise<unknown>) => void) | undefined;
  try {
    const ec = c.executionCtx;
    waitUntil = (p) => ec.waitUntil(p);
  } catch {
    waitUntil = undefined; // no execution context (some test harnesses)
  }
  const r = await discover(c.env, c.var.user, q, lang, {
    ipKey: clientIpKey(c.req.header("cf-connecting-ip") ?? "unknown"),
    waitUntil,
  });
  return c.json(r, r.jobId ? 202 : 200);
});

transferApp.get("/v1/import/jobs/:id", async (c) => {
  const job = await getJob(c.env.DB, c.var.user, c.req.param("id"));
  if (!job) throw new ApiError(404, "not_found");
  return c.json(job);
});
