import type { Context } from "hono";
import { ApiError } from "./errors";

/** Read the request body up to maxBytes: 413 too_large over the cap (declared or streamed). */
export async function readBodyBytes(c: Context, maxBytes: number): Promise<Uint8Array> {
  const declared = Number(c.req.header("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) throw new ApiError(413, "too_large");

  const stream = c.req.raw.body;
  if (!stream) return new Uint8Array(0);
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      throw new ApiError(413, "too_large");
    }
    chunks.push(value);
  }
  const buf = new Uint8Array(total);
  let off = 0;
  for (const ch of chunks) { buf.set(ch, off); off += ch.byteLength; }
  return buf;
}

/** Read a size-bounded JSON object body: 413 over the cap, 400 when not a JSON object. */
export async function readJsonBody(
  c: Context,
  maxBytes = 512 * 1024,
): Promise<Record<string, unknown>> {
  const raw = new TextDecoder().decode(await readBodyBytes(c, maxBytes));
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    throw new ApiError(400, "invalid_input");
  }
  if (typeof v !== "object" || v === null || Array.isArray(v)) throw new ApiError(400, "invalid_input");
  return v as Record<string, unknown>;
}

/**
 * Parse a multipart/form-data body without first buffering it ourselves: a Content-Length check,
 * then a streamed byte counter that errors the stream once maxBytes is passed (-> 413 too_large).
 * Peak memory is the parsed form (about maxBytes), not raw bytes plus form.
 */
export async function readFormDataBounded(c: Context, maxBytes: number): Promise<FormData> {
  const declared = Number(c.req.header("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) throw new ApiError(413, "too_large");
  const body = c.req.raw.body;
  if (!body) throw new ApiError(400, "invalid_input");
  let total = 0;
  let exceeded = false;
  const counter = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, ctrl) {
      total += chunk.byteLength;
      if (total > maxBytes) {
        exceeded = true;
        ctrl.error(new Error("too_large"));
        return;
      }
      ctrl.enqueue(chunk);
    },
  });
  const req = new Request(c.req.url, {
    method: "POST",
    headers: { "content-type": c.req.header("content-type") ?? "" },
    body: body.pipeThrough(counter),
    // @ts-expect-error duplex is required for stream bodies
    duplex: "half",
  });
  try {
    return await req.formData();
  } catch {
    throw exceeded ? new ApiError(413, "too_large") : new ApiError(400, "invalid_input");
  }
}

/**
 * Parse an HTML form body (urlencoded or multipart) with a hard byte cap: 413 too_large over the
 * cap, 400 invalid_input for any other content type or a malformed body. Always returns FormData.
 */
export async function readFormBounded(c: Context, maxBytes: number): Promise<FormData> {
  const ct = (c.req.header("content-type") ?? "").toLowerCase();
  if (ct.startsWith("application/x-www-form-urlencoded")) {
    const raw = new TextDecoder().decode(await readBodyBytes(c, maxBytes));
    const fd = new FormData();
    for (const [k, v] of new URLSearchParams(raw)) fd.append(k, v);
    return fd;
  }
  if (/^multipart\/form-data\s*;/.test(ct)) return readFormDataBounded(c, maxBytes);
  throw new ApiError(400, "invalid_input");
}
