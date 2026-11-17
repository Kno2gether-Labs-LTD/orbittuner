import type { Env } from "../env";
import { ApiError } from "../lib/errors";
import { KEY_NAMES } from "../lib/secrets";

export type ProviderKind = "openai_compat" | "anthropic";
export type ProviderConfig = {
  id: string;
  label: string;
  kind: ProviderKind;
  baseUrl: string;
  model: string;
  keyName: string;
  enabled: boolean;
  timeoutMs?: number;
};

export const DEFAULT_PROVIDERS: ProviderConfig[] = [
  { id: "inworld", label: "Inworld Router", kind: "openai_compat", baseUrl: "https://api.inworld.ai/v1", model: "auto", keyName: "INWORLD_API_KEY", enabled: true },
  { id: "openai", label: "OpenAI", kind: "openai_compat", baseUrl: "https://api.openai.com/v1", model: "gpt-4.1-mini", keyName: "OPENAI_API_KEY", enabled: true },
  { id: "anthropic", label: "Anthropic", kind: "anthropic", baseUrl: "https://api.anthropic.com/v1", model: "claude-haiku-5-5", keyName: "ANTHROPIC_API_KEY", enabled: false },
  { id: "custom1", label: "Custom gateway", kind: "openai_compat", baseUrl: "", model: "", keyName: "CUSTOM_AI_KEY_1", enabled: false },
];

/** Returns normalized origin+pathname (no trailing slash), "" for empty, null when unacceptable. */
export function normalizeBaseUrl(raw: string): string | null {
  if (raw === "") return "";
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash) return null;
  return (u.origin + u.pathname).replace(/\/+$/, "");
}

export const PROVIDER_ID_RE = /^[a-z0-9_-]{1,32}$/;

function isProvider(p: unknown): p is ProviderConfig {
  if (!p || typeof p !== "object") return false;
  const o = p as Record<string, unknown>;
  return (
    typeof o.id === "string" && PROVIDER_ID_RE.test(o.id) &&
    typeof o.label === "string" &&
    (o.kind === "openai_compat" || o.kind === "anthropic") &&
    typeof o.baseUrl === "string" &&
    typeof o.model === "string" &&
    typeof o.keyName === "string" && (KEY_NAMES as readonly string[]).includes(o.keyName) &&
    normalizeBaseUrl(o.baseUrl as string) !== null &&
    typeof o.enabled === "boolean" &&
    (o.timeoutMs === undefined || (typeof o.timeoutMs === "number" && o.timeoutMs > 0))
  );
}

function defaults(env: Env): ProviderConfig[] {
  const override = env.INWORLD_BASE_URL ? normalizeBaseUrl(env.INWORLD_BASE_URL) : null;
  // The value itself is not logged: it is configuration, but keep logs free of it anyway.
  if (env.INWORLD_BASE_URL && !override) console.warn("INWORLD_BASE_URL ignored: not an https URL");
  return DEFAULT_PROVIDERS.map((p) =>
    p.id === "inworld" && override ? { ...p, baseUrl: override } : { ...p },
  );
}

export async function getProviders(env: Env): Promise<ProviderConfig[]> {
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'ai_providers'").first<{ value: string }>();
    const parsed: unknown = row ? JSON.parse(row.value) : null;
    if (Array.isArray(parsed) && parsed.length > 0 && parsed.every(isProvider)) {
      return parsed.map((p) => ({ ...p, baseUrl: normalizeBaseUrl(p.baseUrl)! }));
    }
    // Missing row or the '[]' placeholder means "not configured yet"; anything else is broken.
    if (!(parsed === null || (Array.isArray(parsed) && parsed.length === 0))) {
      console.warn("ai_providers setting is invalid; using built-in defaults");
    }
  } catch {
    console.warn("ai_providers setting is invalid; using built-in defaults");
  }
  return defaults(env);
}

export async function saveProviders(env: Env, list: ProviderConfig[]): Promise<void> {
  if (!Array.isArray(list) || list.length === 0 || !list.every(isProvider)) throw new ApiError(400, "invalid_providers");
  const ids = new Set<string>();
  list = list.map((p) => ({ ...p, baseUrl: normalizeBaseUrl(p.baseUrl)! }));
  for (const p of list) {
    if (ids.has(p.id)) throw new ApiError(400, "invalid_providers");
    ids.add(p.id);
    if (p.enabled && (p.model.trim() === "" || p.baseUrl === "")) throw new ApiError(400, "invalid_providers");
  }
  await env.DB.prepare(
    "INSERT INTO settings (key, value) VALUES ('ai_providers', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
  ).bind(JSON.stringify(list)).run();
}
