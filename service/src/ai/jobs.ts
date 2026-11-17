import type { AuthUser } from "../auth/devices";
import { asRole, isAdmin } from "../auth/roles";
import type { Env } from "../env";
import { validateSong } from "../chordpro/parse";
import { ApiError } from "../lib/errors";
import { ulid } from "../lib/ulid";
import { blockedDedupeKeys, dedupeKey, findVisibleDuplicate, prepareInsert, type Song } from "../songs/repo";
import type { aiConvert } from "./convert";
import { loadConvert, loadFetchPage } from "./lazy";
import { checkAiRate, refundAi, reserveAi, utcDay, type AiReservation } from "../auth/plans";

export type ImportMessage = { jobId: string; idx: number };
export type ItemStatus = "queued" | "done" | "duplicate" | "review" | "failed" | "limit_reached";
export type JobView = {
  id: string;
  kind: "links" | "discover";
  /** The normalised search query (discover jobs only). */
  query?: string;
  status: "queued" | "running" | "done";
  total: number;
  done: number;
  items: { url: string; status: ItemStatus; songId?: string; error?: string }[];
};

export const MAX_LINKS = 200;
/** Arrays longer than this are rejected before any scanning (dedupe happens below it). */
export const MAX_RAW_LINKS = 1_000;
export const MAX_URL_CHARS = 2_048;
/** Mirrors wrangler.jsonc consumers[].max_retries: deliveries are 1 + this. */
export const MAX_RETRIES = 2;
export const PUBLISH_CONFIDENCE = 0.8;
/** Total budget for one item's fetch + AI conversion inside the consumer. */
export const ITEM_DEADLINE_MS = 4 * 60_000;
/** A job with no item started or finished for this long has its abandoned items failed with `timeout`. */
export const STALE_AFTER_MS = 30 * 60_000;
/** An item started more recently than this may still be in flight and is never swept. */
export const STARTED_GRACE_MS = 4 * 60_000 + 60_000; // ITEM_DEADLINE_MS + 60 s
/** Delay before redelivering a message whose AI providers were all unavailable. */
export const AI_RETRY_DELAY_S = 60;
const SEND_CHUNK = 100; // Queue.sendBatch accepts at most 100 messages.

/** A hand-written or pasted song publishes for a user only with at least this many distinct chords... */
export const PUBLISH_MIN_CHORDS = 4;
/** ...and at least this many non-blank lyric lines (plus a title). */
export const PUBLISH_MIN_LYRIC_LINES = 4;

/** The deterministic publish rule: a title, >= 4 distinct chords and >= 4 non-blank lyric lines. */
export function meetsPublishRule(chordpro: string): boolean {
  const v = validateSong(chordpro);
  if (!v.ok) return false;
  const lyricLines = v.doc.lyricsText.split("\n").filter((l) => l.trim() !== "").length;
  return v.doc.chords.length >= PUBLISH_MIN_CHORDS && lyricLines >= PUBLISH_MIN_LYRIC_LINES;
}

/**
 * Where a new song lands. Callers check visible duplicates first (409 / duplicate item) and force
 * review for a copy of a hidden or moderated song (see blockedDedupeKeys).
 *   - Owner/admin: always published.
 *   - Anonymous user (no Google identity): always review.
 *   - Linked user, AI result (`confidence` given): published at confidence >= 0.8 AND when the
 *     result meets the deterministic rule; else review.
 *   - Linked user, no AI: published when `chordpro` meets the deterministic rule; else review.
 *   - No chordpro to judge: review.
 */
export function importStatus(
  user: Pick<AuthUser, "role" | "linked">, confidence?: number, chordpro?: string,
): Song["status"] {
  if (isAdmin(user.role)) return "published";
  if (!user.linked || chordpro === undefined) return "review";
  if (confidence !== undefined && confidence < PUBLISH_CONFIDENCE) return "review";
  return meetsPublishRule(chordpro) ? "published" : "review";
}

// ---------- plan quota charging (one unit per AI action; see auth/plans.ts) ----------

/**
 * Run one AI action under the user's plan allowance: reserve one unit (429 plan_limit_reached when
 * spent), run, and refund the unit when the action failed before any model call, i.e. it failed
 * before reaching aiConvert (page fetch, SSRF) or aiConvert reported `modelCalled: false`
 * (empty_text, pre-check no_chords, ai_limit_reached, ai_unavailable before any attempt).
 * Once a model was called the unit stays spent. Owners and admins reserve nothing.
 */
export async function withAiCharge<T>(
  env: Env, user: AuthUser, run: (convert: typeof aiConvert) => Promise<T>,
): Promise<T> {
  const { aiConvert } = await loadConvert();
  const res = await reserveAi(env, user, 1);
  let started = false;
  const tracked: typeof aiConvert = (e, input) => {
    started = true;
    return aiConvert(e, input);
  };
  try {
    return await run(tracked);
  } catch (e) {
    if (!started || (e instanceof ApiError && e.modelCalled === false)) await refundAi(env, res, 1);
    throw e;
  }
}

/** aiConvert for a user-initiated text conversion, charged to the user's plan allowance. */
export async function aiConvertCharged(
  env: Env, user: AuthUser, input: Parameters<typeof aiConvert>[1],
): Promise<Awaited<ReturnType<typeof aiConvert>>> {
  return withAiCharge(env, user, (convert) => convert(env, input));
}

// ---------- job creation and reading ----------

/** Validate `urls` (strings, trimmed, deduped to 1..200; raw array <= 1000) or throw 400 invalid_input. */
export function parseUrls(v: unknown): string[] {
  if (!Array.isArray(v) || v.length === 0 || v.length > MAX_RAW_LINKS) throw new ApiError(400, "invalid_input");
  const out = new Set<string>();
  for (const u of v) {
    if (typeof u !== "string" || u.length > MAX_URL_CHARS) throw new ApiError(400, "invalid_input");
    const t = u.trim();
    if (t) out.add(t);
  }
  if (out.size === 0 || out.size > MAX_LINKS) throw new ApiError(400, "invalid_input");
  return [...out];
}

/** One item of a new job: queued items get a queue message; the rest are final from the start. */
export type NewJobItem = { u: string; s: ItemStatus; e: string | null; id?: string | null };
export type JobMeta = { kind: "links" | "discover"; query?: string | null };

/**
 * Create a job (one AI-capable request against the per-minute rate limit): URLs that fail the SSRF
 * guard are recorded as failed straight away; the rest are queued (one message each) after one
 * unit per queued URL of the user's plan allowance is reserved, all or nothing.
 */
export async function createLinkJob(env: Env, user: AuthUser, urls: string[]): Promise<string> {
  await checkAiRate(env, user);
  const { assertPublicUrl } = await loadFetchPage();
  const items = urls.map((url): NewJobItem => {
    try {
      assertPublicUrl(url);
      return { u: url, s: "queued", e: null };
    } catch (e) {
      return { u: url, s: "failed", e: e instanceof ApiError ? e.code : "bad_url" };
    }
  });
  const reservation = await reserveAi(env, user, items.filter((it) => it.s === "queued").length);
  return insertJob(env, user, items, reservation, { kind: "links" });
}

/**
 * Write a job and its items, then send one queue message per queued item. The caller has already
 * rate-limited the request and reserved one unit per queued item in `reservation`. Rows are written
 * before messages are sent; if a send fails, the unsent items are failed `queue_unavailable`, their
 * units refunded, and 503 queue_unavailable is thrown when nothing at all was sent.
 */
export async function insertJob(
  env: Env, user: AuthUser, items: NewJobItem[], reservation: AiReservation, meta: JobMeta,
): Promise<string> {
  const queued = items.flatMap((it, idx) => (it.s === "queued" ? [idx] : []));
  const id = ulid();
  const done = items.length - queued.length;
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO import_jobs (id, user_id, created_at, total, done, status, last_progress_at, kind, query, ip_key)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      id, user.id, Date.now(), items.length, done, done === items.length ? "done" : "queued", Date.now(),
      meta.kind, meta.query ?? null, reservation?.ipKey ?? null,
    ),
    // All items in one statement: the list travels as a single JSON bind (D1 caps binds at 100).
    env.DB.prepare(
      `INSERT INTO import_job_items (job_id, idx, url, status, song_id, error)
       SELECT ?, CAST(key AS INTEGER), json_extract(value, '$.u'), json_extract(value, '$.s'),
              json_extract(value, '$.id'), json_extract(value, '$.e')
       FROM json_each(?)`,
    ).bind(id, JSON.stringify(items)),
  ]);

  let sentCount = 0;
  try {
    for (; sentCount < queued.length; sentCount += SEND_CHUNK) {
      await env.IMPORT_QUEUE.sendBatch(
        queued.slice(sentCount, sentCount + SEND_CHUNK).map((idx) => ({ body: { jobId: id, idx } })),
      );
    }
  } catch (e) {
    console.error("import queue send failed", e instanceof Error ? e.name : "error");
    const unsent = queued.slice(sentCount);
    try {
      await env.DB.batch([
        env.DB.prepare(
          `UPDATE import_job_items SET status = 'failed', error = 'queue_unavailable'
            WHERE job_id = ? AND status = 'queued' AND idx IN (SELECT value FROM json_each(?))`,
        ).bind(id, JSON.stringify(unsent)),
        recount(env.DB, id),
      ]);
    } catch (e2) {
      // Unsent items stay queued and are later failed by the stale sweep; still refund below.
      console.error("import compensation failed", e2 instanceof Error ? e2.name : "error");
    }
    try {
      await refundAi(env, reservation, unsent.length);
    } catch (e3) {
      console.error("import refund failed", e3 instanceof Error ? e3.name : "error");
    }
    if (sentCount === 0) throw new ApiError(503, "queue_unavailable");
  }
  return id;
}

/**
 * When the job has made no progress for STALE_AFTER_MS, fail its queued items that were never
 * started or were started longer ago than any live consumer could still hold them, then recount.
 * Both statements are gated in SQL, so for a fresh or finished job they are cheap no-ops.
 */
function sweepStmts(db: D1Database, jobId: string): D1PreparedStatement[] {
  const now = Date.now();
  const idleBefore = now - STALE_AFTER_MS;
  return [
    db.prepare(
      `UPDATE import_job_items SET status = 'failed', error = 'timeout'
        WHERE job_id = ? AND status = 'queued' AND (started_at IS NULL OR started_at < ?)
          AND EXISTS (SELECT 1 FROM import_jobs WHERE id = ? AND status != 'done' AND last_progress_at < ?)`,
    ).bind(jobId, now - STARTED_GRACE_MS, jobId, idleBefore),
    // Recount without touching last_progress_at: a sweep is not progress.
    db.prepare(
      `UPDATE import_jobs
          SET done = (SELECT COUNT(*) FROM import_job_items WHERE job_id = ? AND status != 'queued'),
              status = CASE WHEN (SELECT COUNT(*) FROM import_job_items WHERE job_id = ? AND status != 'queued') >= total
                            THEN 'done' ELSE status END
        WHERE id = ? AND status != 'done' AND last_progress_at < ?`,
    ).bind(jobId, jobId, jobId, idleBefore),
  ];
}

export async function getJob(db: D1Database, user: AuthUser, id: string): Promise<JobView | null> {
  const job = await db
    .prepare("SELECT id, kind, query, status, total, done, last_progress_at FROM import_jobs WHERE id = ? AND user_id = ?")
    .bind(id, user.id)
    .first<{
      id: string; kind: JobView["kind"] | null; query: string | null; status: JobView["status"];
      total: number; done: number; last_progress_at: number | null;
    }>();
  if (!job) return null;
  let { status, done } = job;
  if (status !== "done" && (job.last_progress_at ?? 0) < Date.now() - STALE_AFTER_MS) {
    await db.batch(sweepStmts(db, id));
    const fresh = await db.prepare("SELECT status, done FROM import_jobs WHERE id = ?").bind(id)
      .first<{ status: JobView["status"]; done: number }>();
    if (fresh) ({ status, done } = fresh);
  }
  const { results } = await db
    .prepare("SELECT url, status, song_id, error FROM import_job_items WHERE job_id = ? ORDER BY idx")
    .bind(id)
    .all<{ url: string; status: ItemStatus; song_id: string | null; error: string | null }>();
  return {
    id: job.id, kind: job.kind ?? "links", ...(job.query ? { query: job.query } : {}),
    status, total: job.total, done,
    items: results.map((r) => ({
      url: r.url, status: r.status,
      ...(r.song_id ? { songId: r.song_id } : {}),
      ...(r.error ? { error: r.error } : {}),
    })),
  };
}
// ---------- queue consumer ----------

/**
 * Recount finished items (idempotent, unlike done = done + 1), close the job when all are finished,
 * and stamp last_progress_at (every caller has just finished one or more items).
 */
function recount(db: D1Database, jobId: string): D1PreparedStatement {
  return db
    .prepare(
      `UPDATE import_jobs
          SET done = (SELECT COUNT(*) FROM import_job_items WHERE job_id = ? AND status != 'queued'),
              status = CASE WHEN (SELECT COUNT(*) FROM import_job_items WHERE job_id = ? AND status != 'queued') >= total
                            THEN 'done' ELSE 'running' END,
              last_progress_at = ?
        WHERE id = ?`,
    )
    .bind(jobId, jobId, Date.now(), jobId);
}

/** Move one still-queued item to a final status. False when it was already final (redelivery). */
function finishStmt(
  db: D1Database, m: ImportMessage, status: ItemStatus, songId: string | null, error: string | null,
): D1PreparedStatement {
  return db
    .prepare(
      "UPDATE import_job_items SET status = ?, song_id = ?, error = ? WHERE job_id = ? AND idx = ? AND status = 'queued'",
    )
    .bind(status, songId, error, m.jobId, m.idx);
}

/** True when this call moved the item (false: it was already final). */
async function finish(
  db: D1Database, m: ImportMessage, status: ItemStatus, songId: string | null, error: string | null,
): Promise<boolean> {
  const res = await db.batch([finishStmt(db, m, status, songId, error), recount(db, m.jobId)]);
  return res[0].meta.changes > 0;
}

/**
 * The AI budget is spent: close this item and every item of the job that no consumer has started.
 * Items already in flight on another message are left to finish (or hit the limit) on their own.
 * Returns how many items it closed (none of them reached a model call).
 */
async function stopJob(db: D1Database, m: ImportMessage): Promise<number> {
  const res = await db.batch([
    db.prepare(
      `UPDATE import_job_items SET status = 'limit_reached', error = 'ai_limit_reached'
        WHERE job_id = ? AND status = 'queued' AND (idx = ? OR started_at IS NULL)`,
    ).bind(m.jobId, m.idx),
    recount(db, m.jobId),
  ]);
  return res[0].meta.changes;
}

/** Thrown to ask the queue for a delayed redelivery; `code` is recorded if retries run out. */
export class RetryLater extends Error {
  constructor(public readonly code: string, public readonly delaySeconds: number) {
    super(code);
    this.name = "RetryLater";
  }
}

function withDeadline<T>(p: Promise<T>, ms: number, onExpire?: () => void): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      onExpire?.();
      reject(new ApiError(504, "timeout"));
    }, ms);
  });
  return Promise.race([p, timeout]).finally(() => clearTimeout(timer));
}

type ItemRow = {
  url: string; status: ItemStatus; user_id: string; role: string | null; linked: number | null; created_at: number;
  /** Per-IP key the job's units were also reserved under (anonymous accounts), else null. */
  ip_key: string | null;
};
export type ConsumerOptions = { deadlineMs?: number };

/**
 * Process one item: fetch -> AI convert -> dedupe -> insert. Expected failures (ApiError) become
 * item statuses; ai_unavailable throws RetryLater; anything else is thrown so the queue retries.
 */
export async function processImportItem(env: Env, m: ImportMessage, opts: ConsumerOptions = {}): Promise<void> {
  const db = env.DB;
  // Sweep this job's stale items first; then read the item (one round trip).
  const res0 = await db.batch([
    ...sweepStmts(db, m.jobId),
    db.prepare(
      `SELECT i.url, i.status, j.user_id, u.role, u.google_sub IS NOT NULL AS linked, j.created_at, j.ip_key FROM import_job_items i
         JOIN import_jobs j ON j.id = i.job_id LEFT JOIN users u ON u.id = j.user_id
        WHERE i.job_id = ? AND i.idx = ?`,
    ).bind(m.jobId, m.idx),
  ]);
  const row = (res0[2].results as ItemRow[])[0];
  if (!row || row.status !== "queued") return; // unknown, stale or already finished: idempotent no-op

  await db.batch([
    db.prepare(
      "UPDATE import_jobs SET status = CASE WHEN status = 'queued' THEN 'running' ELSE status END, last_progress_at = ? WHERE id = ?",
    ).bind(Date.now(), m.jobId),
    db.prepare("UPDATE import_job_items SET started_at = ? WHERE job_id = ? AND idx = ? AND status = 'queued'")
      .bind(Date.now(), m.jobId, m.idx),
  ]);
  const user: AuthUser = { id: row.user_id, name: "", role: asRole(row.role), linked: row.linked === 1 };

  // The item's unit was reserved on the job's creation day; give it back (per the modelCalled
  // rule) when the item ends without any model call, to the per-IP counter too when one was
  // charged. Owners and admins reserved nothing.
  const reservation: AiReservation = isAdmin(user.role)
    ? null
    : { userId: user.id, day: utcDay(row.created_at), ...(row.ip_key ? { ipKey: row.ip_key } : {}) };
  let aiStarted = false;
  let expired = false;
  let converted: Awaited<ReturnType<typeof aiConvert>>;
  try {
    const [{ fetchPage }, { aiConvert }] = await Promise.all([loadFetchPage(), loadConvert()]);
    converted = await withDeadline(
      (async () => {
        const pageRes = await fetchPage(env, row.url);
        // Past the deadline the item is already failed: never start a model call for it.
        if (expired) throw new ApiError(504, "timeout");
        aiStarted = true;
        return aiConvert(env, { text: pageRes.text, sourceUrl: row.url });
      })(),
      opts.deadlineMs ?? ITEM_DEADLINE_MS,
      () => { expired = true; },
    );
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    if (e.code === "ai_limit_reached") {
      // This item and every never-started item closed here: none reached a model call.
      await refundAi(env, reservation, await stopJob(db, m));
      return;
    }
    if (e.code === "ai_unavailable") throw new RetryLater(e.code, AI_RETRY_DELAY_S);
    // A deadline hit after aiConvert started may have spent a model call: no refund then.
    const noModelCall = !aiStarted || e.modelCalled === false;
    if ((await finish(db, m, "failed", null, e.code)) && noModelCall) await refundAi(env, reservation, 1);
    return;
  }

  const v = validateSong(converted.chordpro);
  if (!v.ok || !v.doc.meta.title.trim()) {
    await finish(db, m, "failed", null, "ai_unparseable");
    return;
  }
  const dup = await findVisibleDuplicate(db, user, v.doc.meta.artist?.trim() || null, v.doc.meta.title.trim());
  if (dup) {
    await finish(db, m, "duplicate", dup, null);
    return;
  }

  const blocked = !isAdmin(user.role) &&
    (await blockedDedupeKeys(db, [dedupeKey(v.doc.meta.artist?.trim() || null, v.doc.meta.title.trim())])).size > 0;
  const status = blocked ? "review" : importStatus(user, converted.confidence ?? 0, converted.chordpro);
  // Item status mirrors where the song landed: "done" (published) or "review".
  const itemStatus: ItemStatus = status === "published" ? "done" : "review";
  const songId = ulid();
  // One transaction: claim the item first (only if still queued), then insert the song only if the
  // claim is ours, so a concurrent delivery or a limit stop can never leave a second copy.
  await db.batch([
    finishStmt(db, m, itemStatus, songId, null),
    ...prepareInsert(db, {
      id: songId, chordpro: converted.chordpro, language: converted.language, categories: converted.categories,
      source: "web", sourceUrl: row.url, status, createdBy: user.id,
    }, {
      sql: "EXISTS (SELECT 1 FROM import_job_items WHERE job_id = ? AND idx = ? AND song_id = ?)",
      binds: [m.jobId, m.idx, songId],
    }).stmts,
    recount(db, m.jobId),
  ]);
}

function isMessage(b: unknown): b is ImportMessage {
  const o = b as ImportMessage | null;
  return !!o && typeof o.jobId === "string" && Number.isInteger(o.idx);
}

async function handleMessage(msg: Message<unknown>, env: Env, opts: ConsumerOptions): Promise<void> {
  if (!isMessage(msg.body)) {
    msg.ack();
    return;
  }
  try {
    await processImportItem(env, msg.body, opts);
    msg.ack();
  } catch (e) {
    const code = e instanceof RetryLater ? e.code : "internal";
    if (!(e instanceof RetryLater)) console.error("import item failed", e instanceof Error ? e.name : "error");
    if (msg.attempts > MAX_RETRIES) {
      try {
        await finish(env.DB, msg.body, "failed", null, code);
        msg.ack();
      } catch {
        msg.retry();
      }
    } else if (e instanceof RetryLater) {
      msg.retry({ delaySeconds: e.delaySeconds });
    } else {
      msg.retry();
    }
  }
}

/** Queue consumer: messages run concurrently; each is acked or retried on its own. */
export async function handleImportBatch(batch: MessageBatch<unknown>, env: Env, opts: ConsumerOptions = {}): Promise<void> {
  await Promise.allSettled(batch.messages.map((msg) => handleMessage(msg, env, opts)));
}
