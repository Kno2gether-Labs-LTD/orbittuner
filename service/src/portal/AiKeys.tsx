import type { FC } from "hono/jsx";
import type { ProviderConfig } from "../ai/providers";
import type { SecretStatus } from "../lib/secrets";
import { Csrf, Icon, Notice, PageHead } from "./Layout";

/** Built-in gateways: their base URL is fixed (or set at deploy time), only custom ones are editable. */
export const BUILTIN_PROVIDERS = new Set(["inworld", "openai", "anthropic"]);

export const MODEL_SUGGESTIONS: Record<string, string[]> = {
  inworld: ["auto", "anthropic/claude-haiku-5-5", "openai/gpt-4.1-mini"],
  openai: ["gpt-4.1-mini"],
  anthropic: ["claude-haiku-5-5"],
};

export const KEY_LABELS: Record<string, { label: string; hint: string }> = {
  INWORLD_API_KEY: { label: "Inworld", hint: "Inworld Router, the default gateway" },
  OPENAI_API_KEY: { label: "OpenAI", hint: "Direct OpenAI access" },
  ANTHROPIC_API_KEY: { label: "Anthropic", hint: "Direct Anthropic access" },
  APIFY_TOKEN: { label: "Apify", hint: "Fetches pages that block ordinary requests" },
  CUSTOM_AI_KEY_1: { label: "Custom key 1", hint: "For a custom gateway" },
  CUSTOM_AI_KEY_2: { label: "Custom key 2", hint: "For a second custom gateway" },
};

export const LIMITS = {
  daily: { min: 0, max: 10_000 },
  tokens: { min: 256, max: 32_000 },
};

export type AiPageProps = {
  csrf: string;
  providers: ProviderConfig[];
  keys: SecretStatus[];
  secretsOn: boolean;
  dailyLimit: number;
  maxTokens: number;
  usage: number;
  apifyActor: string;
  /** Google Search actor used by Find on the web. */
  apifySearchActor: string;
  notice?: string;
  error?: { section: "gateways" | "keys" | "limits" | "apify"; text: string };
};

const MASK = "••••";

export const KeyChip: FC<{ st?: SecretStatus }> = ({ st }) => {
  if (!st || st.source === "missing") return <span class="chip missing">Missing</span>;
  if (st.unreadable) {
    return (
      <span class="chip warn" title="Saved here, but SECRETS_KEY is missing so it cannot be read">
        Unreadable{st.last4 ? <code>{MASK + st.last4}</code> : null}
      </span>
    );
  }
  return (
    <span class={st.source === "portal" ? "chip portal" : "chip env"}>
      {st.source === "portal" ? "Portal" : "From deploy"}
      {st.last4 ? <code>{MASK + st.last4}</code> : null}
    </span>
  );
};

const host = (u: string) => {
  try {
    return new URL(u).host + new URL(u).pathname.replace(/\/$/, "");
  } catch {
    return u;
  }
};

const SectionError: FC<{ props: AiPageProps; section: NonNullable<AiPageProps["error"]>["section"] }> = ({ props, section }) =>
  props.error?.section === section ? <Notice kind="err">{props.error.text}</Notice> : null;

const Gateways: FC<{ props: AiPageProps }> = ({ props }) => {
  const { providers, keys, csrf } = props;
  const status = new Map(keys.map((k) => [k.name as string, k]));
  return (
    <section class="card ai-card" id="gateways" aria-labelledby="gw-h">
      <div class="ai-card-head">
        <span class="ic">
          <Icon name="spark" />
        </span>
        <div>
          <h2 id="gw-h">Gateway order</h2>
          <p>Each conversion tries the enabled gateways from the top and stops at the first that answers. Put the cheapest first.</p>
        </div>
      </div>
      <SectionError props={props} section="gateways" />
      <form method="post" action="/portal/ai/providers" class="gw-form">
        <Csrf value={csrf} />
        {/* First submit button in the form: Enter in a field saves instead of moving a row. */}
        <button type="submit" class="sr-only" tabindex={-1} aria-hidden="true">
          Save gateways
        </button>
        {Object.entries(MODEL_SUGGESTIONS).map(([id, models]) => (
          <datalist id={`models-${id}`}>
            {models.map((m) => (
              <option value={m}></option>
            ))}
          </datalist>
        ))}
        <ol class="gw-list">
          {providers.map((p, i) => {
            const custom = !BUILTIN_PROVIDERS.has(p.id);
            return (
              <li class={p.enabled ? "gw" : "gw off"} data-gw={p.id}>
                <input type="hidden" name="order" value={p.id} />
                <div class="gw-pos">
                  <span class="gw-n">{i + 1}</span>
                  <span class="gw-move">
                    <button class="icon-btn" type="submit" name="move" value={`${p.id}:up`} disabled={i === 0} aria-label={`Move ${p.label} up`} title="Move up">
                      <Icon name="up" />
                    </button>
                    <button
                      class="icon-btn"
                      type="submit"
                      name="move"
                      value={`${p.id}:down`}
                      disabled={i === providers.length - 1}
                      aria-label={`Move ${p.label} down`}
                      title="Move down"
                    >
                      <Icon name="down" />
                    </button>
                  </span>
                </div>
                <div class="gw-id">
                  <b>{p.label}</b>
                  <small>
                    {p.kind === "anthropic" ? "Anthropic API" : "OpenAI-compatible"}
                    {!custom && p.baseUrl ? <> · {host(p.baseUrl)}</> : null}
                  </small>
                  <KeyChip st={status.get(p.keyName)} />
                </div>
                <div class="gw-fields">
                  <label class="field">
                    <span>Model</span>
                    <input
                      class="input"
                      name={`model_${p.id}`}
                      value={p.model}
                      maxlength={200}
                      list={MODEL_SUGGESTIONS[p.id] ? `models-${p.id}` : undefined}
                      autocomplete="off"
                      spellcheck={false}
                      placeholder={custom ? "model-name" : undefined}
                    />
                  </label>
                  {custom ? (
                    <label class="field">
                      <span>Base URL</span>
                      <input
                        class="input"
                        name={`base_${p.id}`}
                        value={p.baseUrl}
                        maxlength={500}
                        inputmode="url"
                        autocomplete="off"
                        spellcheck={false}
                        placeholder="https://gateway.example.com/v1"
                      />
                    </label>
                  ) : null}
                </div>
                <div class="gw-end">
                  <label class="switch" title={p.enabled ? "Enabled" : "Disabled"}>
                    <input type="checkbox" name={`enabled_${p.id}`} checked={p.enabled} />
                    <span class="track" aria-hidden="true"></span>
                    <span class="sr-only">Use {p.label}</span>
                  </label>
                  <button class="btn sm" type="button" data-test={p.id}>
                    Test
                  </button>
                  <span class="gw-test" data-test-out={p.id} aria-live="polite"></span>
                </div>
              </li>
            );
          })}
        </ol>
        <div class="card-foot">
          <span class="foot-note">Test uses the saved settings and makes one tiny request.</span>
          <button class="btn primary" type="submit">
            Save gateways
          </button>
        </div>
      </form>
    </section>
  );
};

const Keys: FC<{ props: AiPageProps }> = ({ props }) => (
  <section class="card ai-card" id="keys" aria-labelledby="keys-h">
    <div class="ai-card-head">
      <span class="ic">
        <Icon name="key" />
      </span>
      <div>
        <h2 id="keys-h">API keys</h2>
        <p>Keys saved here are encrypted and override the ones set at deploy time. Only the last four characters are ever shown.</p>
      </div>
    </div>
    <SectionError props={props} section="keys" />
    <div class="keys">
      {props.keys.map((k) => {
        const meta = KEY_LABELS[k.name] ?? { label: k.name, hint: "" };
        const fid = `key-${k.name}`;
        return (
          <div class="key-row">
            <div class="key-id">
              <label for={fid}>
                <b>{meta.label}</b>
              </label>
              <small>
                <code>{k.name}</code> · {meta.hint}
              </small>
              <KeyChip st={k} />
            </div>
            <form method="post" action="/portal/ai/keys" class="key-set">
              <Csrf value={props.csrf} />
              <input type="hidden" name="name" value={k.name} />
              <input type="hidden" name="intent" value="save" />
              <input
                class="input"
                id={fid}
                type="password"
                name="value"
                required
                minlength={8}
                maxlength={4096}
                autocomplete="new-password"
                spellcheck={false}
                placeholder={k.source === "missing" ? "Paste key" : "Replace key"}
                disabled={!props.secretsOn}
              />
              <button class="btn sm" type="submit" disabled={!props.secretsOn}>
                Save
              </button>
            </form>
            {k.source === "portal" ? (
              <form method="post" action="/portal/ai/keys" class="key-del" data-confirm={`Remove the ${meta.label} key saved here?`}>
                <Csrf value={props.csrf} />
                <input type="hidden" name="name" value={k.name} />
                <input type="hidden" name="intent" value="remove" />
                <button class="btn sm quiet" type="submit">
                  Remove
                </button>
              </form>
            ) : (
              <span class="key-del" aria-hidden="true"></span>
            )}
          </div>
        );
      })}
    </div>
  </section>
);

const Limits: FC<{ props: AiPageProps }> = ({ props }) => {
  const pct = props.dailyLimit > 0 ? Math.min(100, Math.round((props.usage / props.dailyLimit) * 100)) : 100;
  return (
    <section class="card ai-card" id="limits" aria-labelledby="lim-h">
      <div class="ai-card-head">
        <span class="ic">
          <Icon name="gauge" />
        </span>
        <div>
          <h2 id="lim-h">Spending limits</h2>
          <p>A hard stop on model calls for the whole library, counted per UTC day.</p>
        </div>
      </div>
      <SectionError props={props} section="limits" />
      <div class="usage" data-usage={String(props.usage)}>
        <div class="usage-num">
          <b>{props.usage}</b>
          <span>of {props.dailyLimit} conversions used today</span>
        </div>
        <meter min={0} max={Math.max(1, props.dailyLimit)} low={Math.max(1, props.dailyLimit) * 0.7} high={Math.max(1, props.dailyLimit) * 0.9} optimum={0} value={props.usage}>
          {pct}%
        </meter>
      </div>
      <form method="post" action="/portal/ai/limits">
        <Csrf value={props.csrf} />
        <label class="setting">
          <span>
            <h3>Conversions per day</h3>
            <p>When reached, AI imports pause until midnight UTC. 0 turns AI off.</p>
          </span>
          <input class="input" type="number" name="ai_daily_limit" min={LIMITS.daily.min} max={LIMITS.daily.max} step={1} required value={String(props.dailyLimit)} />
        </label>
        <label class="setting">
          <span>
            <h3>Longest answer (tokens)</h3>
            <p>Caps each reply. A long song needs about 2,000–4,000.</p>
          </span>
          <input class="input" type="number" name="ai_max_output_tokens" min={LIMITS.tokens.min} max={LIMITS.tokens.max} step={1} required value={String(props.maxTokens)} />
        </label>
        <div class="card-foot">
          <button class="btn primary" type="submit">
            Save limits
          </button>
        </div>
      </form>
    </section>
  );
};

const Apify: FC<{ props: AiPageProps }> = ({ props }) => (
  <section class="card ai-card" id="apify" aria-labelledby="apify-h">
    <div class="ai-card-head">
      <span class="ic">
        <Icon name="link" />
      </span>
      <div>
        <h2 id="apify-h">Apify</h2>
        <p>Fetches song pages that refuse a direct request, and runs the Google searches behind Find on the web.</p>
        <div class="head-chip">
          <KeyChip st={props.keys.find((k) => k.name === "APIFY_TOKEN")} />
        </div>
      </div>
    </div>
    <SectionError props={props} section="apify" />
    <form method="post" action="/portal/ai/apify">
      <Csrf value={props.csrf} />
      <label class="setting">
        <span>
          <h3>Actor</h3>
          <p>
            In the form <code>user~actor</code>. The token is set under API keys.
          </p>
        </span>
        <input class="input mono" name="apify_actor" maxlength={201} required value={props.apifyActor} spellcheck={false} autocomplete="off" />
      </label>
      <label class="setting">
        <span>
          <h3>Search actor</h3>
          <p>
            Runs the web search for Find on the web. Each uncached search is one run; daily search limits are under Settings.
          </p>
        </span>
        <input class="input mono" name="apify_search_actor" maxlength={201} required value={props.apifySearchActor} spellcheck={false} autocomplete="off" />
      </label>
      <div class="card-foot">
        <button class="btn" type="submit">
          Save actors
        </button>
      </div>
    </form>
  </section>
);

export const AiKeysPage: FC<AiPageProps> = (props) => (
  <>
    <PageHead title="AI & keys" sub="Choose which AI gateways convert songs, in what order, and how much they may spend." />
    {!props.secretsOn ? (
      <div class="notice err" role="alert" data-secrets-warning>
        <Icon name="info" />
        <div>
          <b>SECRETS_KEY is not set.</b> Keys cannot be saved here until it is added as a Worker secret. Keys set at deploy time still work.
        </div>
      </div>
    ) : null}
    {props.notice ? <Notice>{props.notice}</Notice> : null}
    <Gateways props={props} />
    <div class="ai-grid">
      <div class="ai-col">
        <Keys props={props} />
      </div>
      <div class="ai-col">
        <Limits props={props} />
        <Apify props={props} />
      </div>
    </div>
  </>
);
