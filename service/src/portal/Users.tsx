import type { FC } from "hono/jsx";
import type { AuthUser } from "../auth/devices";
import { effectivePlan } from "../auth/plans";
import { Csrf, Icon, Notice, PageHead, StatStrip, ago, initials } from "./Layout";
import { canManage, USERS_PAGE_SIZE, type UserCounts, type UserKind, type UserRow, type UsersQuery } from "./staff";

const KIND_TABS: { v: UserKind; label: string }[] = [
  { v: "all", label: "All" },
  { v: "google", label: "Google" },
  { v: "anonymous", label: "Anonymous" },
  { v: "plus", label: "Plus" },
  { v: "staff", label: "Staff" },
  { v: "disabled", label: "Disabled" },
];

export function usersHref(q: UsersQuery, patch: Partial<UsersQuery> = {}): string {
  const m = { ...q, ...patch };
  const p = new URLSearchParams();
  if (m.q) p.set("q", m.q);
  if (m.kind !== "all") p.set("kind", m.kind);
  if (m.page) p.set("page", String(m.page));
  const s = p.toString();
  return s ? `/portal/users?${s}` : "/portal/users";
}

const shortId = (id: string) => id.slice(-8).toLowerCase();
const day = (ms: number) => new Date(ms).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const ROLE_LABEL = { owner: "Owner", admin: "Admin", user: "User" } as const;

/** The name people see for an account: its Google email, a staff name, or "Anonymous". */
function identity(u: UserRow): { title: string; anon: boolean } {
  if (u.email) return { title: u.email, anon: false };
  if (u.role !== "user") return { title: u.name, anon: false };
  return { title: "Anonymous", anon: true };
}

const PlanChip: FC<{ u: UserRow }> = ({ u }) => {
  // Staff have no plan allowance: only the global AI limit applies.
  if (u.role !== "user") return <span class="chip env" title="Owner and admins are bounded only by the global AI limit">Unlimited</span>;
  const plan = effectivePlan(u.plan, u.plan_expires_at);
  if (plan === "plus") {
    return (
      <span class="chip plus" title={u.plan_expires_at ? `Plus until ${day(u.plan_expires_at)}` : "Plus, no end date"}>
        Plus{u.plan_expires_at ? <small>to {day(u.plan_expires_at)}</small> : null}
      </span>
    );
  }
  return <span class={u.plan === "plus" ? "chip missing" : "chip"}>{u.plan === "plus" ? "Plus ended" : "Free"}</span>;
};

const ActionForm: FC<{ csrf: string; action: string; back: string; confirm?: string; children?: unknown }> = ({ csrf, action, back, confirm, children }) => (
  <form method="post" action={action} data-confirm={confirm}>
    <Csrf value={csrf} />
    <input type="hidden" name="back" value={back} />
    {children as never}
  </form>
);

const Manage: FC<{ csrf: string; actor: AuthUser; u: UserRow; back: string }> = ({ csrf, actor, u, back }) => {
  const base = `/portal/users/${encodeURIComponent(u.id)}`;
  const who = identity(u).title;
  const can = (a: Parameters<typeof canManage>[2]) => canManage(actor, u, a);
  const plan = effectivePlan(u.plan, u.plan_expires_at);
  const panels = [];
  if (can("plan")) {
    panels.push(
      <ActionForm csrf={csrf} action={`${base}/plan`} back={back}>
        <fieldset class="mg">
          <legend>Plan</legend>
          <div class="plan-pick" role="radiogroup" aria-label="Plan">
            <label>
              <input type="radio" name="plan" value="free" checked={plan === "free"} />
              <span>Free</span>
            </label>
            <label>
              <input type="radio" name="plan" value="plus" checked={plan === "plus"} />
              <span>Plus</span>
            </label>
          </div>
          <label class="field">
            <span>Plus until (optional)</span>
            <input class="input" type="date" name="expires" data-plan-date value={plan === "plus" && u.plan_expires_at ? isoDay(u.plan_expires_at) : ""} />
          </label>
          <button class="btn sm" type="submit">
            Save plan
          </button>
        </fieldset>
      </ActionForm>,
    );
  }
  if (can("devices") || can("access")) {
    panels.push(
      <div class="mg">
        <h4>Access</h4>
        <p>
          {u.devices} active {u.devices === 1 ? "device" : "devices"}
          {u.disabled_at ? ` · disabled ${ago(u.disabled_at)}` : ""}
        </p>
        <div class="mg-acts">
          {can("devices") && u.devices > 0 ? (
            <ActionForm csrf={csrf} action={`${base}/revoke-devices`} back={back} confirm={`Sign ${who} out on every device? Their app starts a new anonymous account on next launch.`}>
              <button class="btn sm" type="submit">
                Sign out everywhere
              </button>
            </ActionForm>
          ) : null}
          {can("access") ? (
            u.disabled_at ? (
              <ActionForm csrf={csrf} action={`${base}/access`} back={back}>
                <input type="hidden" name="action" value="enable" />
                <button class="btn sm" type="submit">
                  Enable account
                </button>
              </ActionForm>
            ) : (
              <ActionForm csrf={csrf} action={`${base}/access`} back={back} confirm={`Disable ${who}? Their devices stop syncing and adding songs at once.`}>
                <input type="hidden" name="action" value="disable" />
                <button class="btn sm danger" type="submit">
                  Disable account
                </button>
              </ActionForm>
            )
          ) : null}
        </div>
      </div>,
    );
  }
  if (can("role")) {
    panels.push(
      <div class="mg">
        <h4>Role</h4>
        <p>{u.role === "admin" ? "Moderates songs and manages users." : u.linked ? "Admins moderate songs and manage users." : "Only Google-signed-in accounts can become admins."}</p>
        <div class="mg-acts">
          {u.role === "admin" ? (
            <ActionForm csrf={csrf} action={`${base}/role`} back={back} confirm={`Remove admin from ${who}?`}>
              <input type="hidden" name="role" value="user" />
              <button class="btn sm" type="submit">
                Remove admin
              </button>
            </ActionForm>
          ) : u.linked ? (
            <ActionForm csrf={csrf} action={`${base}/role`} back={back} confirm={`Make ${who} an admin? They can moderate songs and manage users.`}>
              <input type="hidden" name="role" value="admin" />
              <button class="btn sm" type="submit">
                Make admin
              </button>
            </ActionForm>
          ) : null}
        </div>
      </div>,
    );
  }
  if (can("login")) {
    panels.push(
      <div class="mg">
        <h4>Portal sign-in</h4>
        <p>{u.has_password ? "Has a portal password. A new link lets them choose a new one." : "No portal password yet. Send them a one-time link to set one."}</p>
        <div class="mg-acts">
          <form method="post" action={`${base}/portal-login`}>
            <Csrf value={csrf} />
            <button class="btn sm" type="submit">
              <Icon name="key" />
              {u.has_password ? "New sign-in link" : "Create portal login"}
            </button>
          </form>
        </div>
      </div>,
    );
  }
  return (
    <div class="manage">
      <dl class="facts">
        <div>
          <dt>Account</dt>
          <dd class="mono">{u.id}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{u.created_at ? day(u.created_at) : "Unknown"}</dd>
        </div>
        <div class="m-only">
          <dt>Last seen</dt>
          <dd>{ago(u.last_seen_at)}</dd>
        </div>
        <div class="m-only">
          <dt>AI today</dt>
          <dd>{u.ai_today}</dd>
        </div>
        <div class="m-only">
          <dt>Songs added</dt>
          <dd>{u.songs}</dd>
        </div>
        <div>
          <dt>Sign-in</dt>
          <dd>{u.linked ? "Google" : u.role === "user" ? "Anonymous (this device only)" : "Portal password"}</dd>
        </div>
      </dl>
      {panels.length ? <div class="mg-grid">{panels}</div> : <p class="faint mg-none">Only the owner can change this account.</p>}
    </div>
  );
};

export const UsersPage: FC<{
  csrf: string;
  actor: AuthUser;
  query: UsersQuery;
  items: UserRow[];
  total: number;
  hasNext: boolean;
  counts: UserCounts;
  back: string;
}> = ({ csrf, actor, query, items, total, hasNext, counts, back }) => {
  const from = query.page * USERS_PAGE_SIZE + 1;
  const filtered = !!query.q || query.kind !== "all";
  return (
    <>
      <PageHead
        title="Users"
        sub="Every app install gets an account. Set plans, sign devices out and disable abuse here; names and emails never reach other users."
      />
      <StatStrip
        label="Accounts"
        stats={[
          { key: "total", label: "Accounts", value: counts.total },
          { key: "google", label: "Signed in with Google", value: counts.google },
          { key: "anonymous", label: "Anonymous", value: counts.anonymous },
          { key: "plus", label: "On Plus", value: counts.plus },
          { key: "today", label: "Active today", value: counts.today },
        ]}
      />
      <form class="filters" method="get" action="/portal/users" role="search">
        <label class="search">
          <span class="sr-only">Search users</span>
          <Icon name="search" />
          <input class="input" type="search" name="q" value={query.q} placeholder="Search by email or account id" maxlength={200} />
        </label>
        {query.kind !== "all" ? <input type="hidden" name="kind" value={query.kind} /> : null}
        <button class="btn sr-only" type="submit">
          Search
        </button>
        <nav class="seg" aria-label="Kind">
          {KIND_TABS.map((t) => (
            <a href={usersHref(query, { kind: t.v, page: 0 })} aria-current={query.kind === t.v ? "true" : undefined}>
              {t.label}
            </a>
          ))}
        </nav>
      </form>
      <div class="card">
        {items.length === 0 ? (
          <div class="empty">
            <Icon name="people" />
            <h3>{filtered ? "No one matches" : "No accounts yet"}</h3>
            <p>{filtered ? "Try part of an email, or the last characters of an account id." : "Accounts appear here as soon as someone opens the app."}</p>
            {filtered ? (
              <a class="btn" href="/portal/users">
                Clear filters
              </a>
            ) : null}
          </div>
        ) : (
          <>
            <div class="users-head" aria-hidden="true">
              <span>Account</span>
              <span>Plan</span>
              <span>Role</span>
              <span>Last seen</span>
              <span class="num">AI today</span>
              <span class="num">Songs</span>
              <span></span>
            </div>
            <ul class="users">
              {items.map((u) => {
                const id = identity(u);
                return (
                  <li data-user={u.id}>
                    <details class={u.disabled_at ? "user off" : "user"}>
                      <summary>
                        <span class="acct">
                          <span class={u.role === "user" ? (id.anon ? "av anon" : "av") : "av owner"}>{id.anon ? "" : initials(id.title)}</span>
                          <span class="pname">
                            <b class={id.anon ? "faint-b" : undefined}>{id.title}</b>
                            <small>
                              <code>{shortId(u.id)}</code> · joined {ago(u.created_at)}
                            </small>
                          </span>
                        </span>
                        <span class="u-plan">
                          <PlanChip u={u} />
                        </span>
                        <span class="u-role">
                          {u.disabled_at ? <span class="status revoked">Disabled</span> : <span class={`role ${u.role}`}>{ROLE_LABEL[u.role]}</span>}
                        </span>
                        <span class="when" data-label="Seen">
                          {ago(u.last_seen_at)}
                        </span>
                        <span class="num" data-ai={String(u.ai_today)} data-label="AI today">
                          {u.ai_today}
                        </span>
                        <span class="num" data-songs={String(u.songs)} data-label="Songs">
                          {u.songs}
                        </span>
                        <span class="chev" aria-hidden="true">
                          <Icon name="chevron" />
                        </span>
                      </summary>
                      <Manage csrf={csrf} actor={actor} u={u} back={back} />
                    </details>
                  </li>
                );
              })}
            </ul>
            {query.page > 0 || hasNext ? (
              <div class="pager">
                <span>
                  {from}–{from + items.length - 1} of {total.toLocaleString("en")}
                </span>
                <div>
                  {query.page > 0 ? (
                    <a class="btn sm" href={usersHref(query, { page: query.page - 1 })}>
                      Previous
                    </a>
                  ) : null}
                  {hasNext ? (
                    <a class="btn sm" href={usersHref(query, { page: query.page + 1 })}>
                      Next
                    </a>
                  ) : null}
                </div>
              </div>
            ) : null}
          </>
        )}
      </div>
    </>
  );
};

export type SettingsValues = {
  anon_ai_daily: string;
  anon_ai_daily_per_ip: string;
  free_ai_daily: string;
  plus_ai_daily: string;
  flag_hide_threshold: string;
  search_daily_anon: string;
  search_daily_free: string;
  search_daily_plus: string;
  search_daily_per_ip: string;
  search_daily_global: string;
  voice_daily_anon: string;
  voice_daily_free: string;
  voice_daily_plus: string;
  voice_daily_per_ip: string;
  voice_daily_global: string;
  stt_order: string;
  inworld_stt_model: string;
  google_client_ids: string;
};

type NumSetting = {
  key: Exclude<keyof SettingsValues, "google_client_ids" | "stt_order" | "inworld_stt_model">;
  group: "ai" | "search" | "voice" | "moderation";
  min: number;
  max: number;
  fallback: number;
  title: string;
  text: string;
};

/** Every numeric setting on the page; fallbacks match the service defaults (plans.ts, searchQuota.ts, repo.ts). */
export const NUM_SETTINGS: NumSetting[] = [
  { key: "anon_ai_daily", group: "ai", min: 0, max: 1000, fallback: 1, title: "Anonymous", text: "For an account that has not signed in with Google." },
  { key: "anon_ai_daily_per_ip", group: "ai", min: 0, max: 10000, fallback: 10, title: "Anonymous, per network", text: "Shared by every anonymous account on one IP address (IPv6 /64), so new installs cannot drain the budget." },
  { key: "free_ai_daily", group: "ai", min: 0, max: 1000, fallback: 3, title: "Free", text: "For a Google-signed-in account." },
  { key: "plus_ai_daily", group: "ai", min: 0, max: 10000, fallback: 50, title: "Plus", text: "For an account on Plus." },
  { key: "search_daily_anon", group: "search", min: 0, max: 1000, fallback: 2, title: "Anonymous", text: "Find on the web searches that are not already cached." },
  { key: "search_daily_free", group: "search", min: 0, max: 1000, fallback: 10, title: "Free", text: "For a Google-signed-in account." },
  { key: "search_daily_plus", group: "search", min: 0, max: 10000, fallback: 50, title: "Plus", text: "For an account on Plus." },
  { key: "search_daily_per_ip", group: "search", min: 0, max: 10000, fallback: 30, title: "Per network", text: "Shared by every non-staff account on one IP address." },
  { key: "search_daily_global", group: "search", min: 0, max: 100000, fallback: 500, title: "Everyone", text: "A circuit breaker on paid web searches for the whole service. Owner and admins count too." },
  { key: "voice_daily_anon", group: "voice", min: 0, max: 1000, fallback: 3, title: "Anonymous", text: "Sing a line clips for an account that has not signed in with Google." },
  { key: "voice_daily_free", group: "voice", min: 0, max: 1000, fallback: 10, title: "Free", text: "For a Google-signed-in account." },
  { key: "voice_daily_plus", group: "voice", min: 0, max: 10000, fallback: 50, title: "Plus", text: "For an account on Plus." },
  { key: "voice_daily_per_ip", group: "voice", min: 0, max: 10000, fallback: 40, title: "Per network", text: "Shared by every non-staff account on one IP address." },
  { key: "voice_daily_global", group: "voice", min: 0, max: 100000, fallback: 1000, title: "Everyone", text: "A circuit breaker on voice clips for the whole service. Owner and admins count too." },
  { key: "flag_hide_threshold", group: "moderation", min: 1, max: 100, fallback: 3, title: "Flags to hide a song", text: "Flags from this many Google-signed-in users hide a song until an admin decides. Anonymous flags never count." },
];

const NumField: FC<{ n: NumSetting; value: string }> = ({ n, value }) => (
  <label class="setting">
    <span>
      <h3>{n.title}</h3>
      <p>{n.text}</p>
    </span>
    <input class="input" type="number" name={n.key} min={n.min} max={n.max} step={1} required value={value} inputmode="numeric" />
  </label>
);

export const SettingsPage: FC<{
  csrf: string;
  values: SettingsValues;
  privateCount: number;
  privateHidden: number;
  privatePublished: number;
  error?: string;
  notice?: string;
}> = ({ csrf, values, privateCount, privateHidden, privatePublished, error, notice }) => (
  <>
    <PageHead title="Settings" sub="Plan allowances, moderation and sign-in for the whole service." />
    {error ? <Notice kind="err">{error}</Notice> : notice ? <Notice>{notice}</Notice> : null}
    <form class="card settings" method="post" action="/portal/settings">
      <Csrf value={csrf} />
      <div class="set-group">
        <h2>AI per day</h2>
        <p>Each conversion, imported link or found page is one action. Owner and admins are only bounded by the global limit on AI &amp; keys.</p>
      </div>
      {NUM_SETTINGS.filter((n) => n.group === "ai").map((n) => <NumField n={n} value={values[n.key]} />)}
      <div class="set-group">
        <h2>Web searches per day</h2>
        <p>Each uncached Find on the web search is one paid Apify run. Cached results are free; converting the pages found counts as AI above.</p>
      </div>
      {NUM_SETTINGS.filter((n) => n.group === "search").map((n) => <NumField n={n} value={values[n.key]} />)}
      <div class="set-group">
        <h2>Sing a line per day</h2>
        <p>Each accepted voice clip is one unit, given back when transcription fails.</p>
      </div>
      {NUM_SETTINGS.filter((n) => n.group === "voice").map((n) => <NumField n={n} value={values[n.key]} />)}
      <label class="setting">
        <span>
          <h3>Speech-to-text order</h3>
          <p>
            Comma list of <code>workers-ai</code> and <code>inworld</code>, tried left to right; a failure falls through to the next. Empty means <code>workers-ai,inworld</code>.
          </p>
        </span>
        <input class="input mono" type="text" name="stt_order" maxlength={40} spellcheck={false} autocomplete="off" placeholder="workers-ai,inworld" value={values.stt_order} />
      </label>
      <label class="setting">
        <span>
          <h3>Inworld speech model</h3>
          <p>Model id sent to Inworld. Empty means <code>groq/whisper-large-v3</code>.</p>
        </span>
        <input class="input mono" type="text" name="inworld_stt_model" maxlength={80} spellcheck={false} autocomplete="off" placeholder="groq/whisper-large-v3" value={values.inworld_stt_model} />
      </label>
      <div class="set-group">
        <h2>Moderation</h2>
      </div>
      {NUM_SETTINGS.filter((n) => n.group === "moderation").map((n) => <NumField n={n} value={values[n.key]} />)}
      <div class="set-group">
        <h2>Google sign-in</h2>
      </div>
      <label class="setting wide">
        <span>
          <h3>OAuth client IDs</h3>
          <p>
            One per line, each ending <code>.apps.googleusercontent.com</code>. Sign-in tokens issued to any other client are refused. Empty turns Google sign-in off.
          </p>
        </span>
        <textarea class="input mono" name="google_client_ids" rows={3} spellcheck={false} autocomplete="off" placeholder="1234567890-abc.apps.googleusercontent.com">
          {values.google_client_ids}
        </textarea>
      </label>
      <div class="card-foot">
        <button class="btn primary" type="submit">
          Save settings
        </button>
      </div>
    </form>

    <section class="card settings" id="private" data-private-count={String(privateCount)}>
      <div class="setting">
        <span>
          <h3>Private songs</h3>
          <p>
            {privateCount === 0
              ? "No songs added by you or admins carry the category private."
              : `${privateCount} ${privateCount === 1 ? "song" : "songs"} added by you or admins ${privateCount === 1 ? "carries" : "carry"} the category private; ${privateHidden} ${privateHidden === 1 ? "is" : "are"} hidden. Hidden songs stay visible to staff only. Members' songs and songs in review are never changed here.`}
          </p>
        </span>
        <span class="private-count" aria-hidden="true">
          <b>{privateHidden}</b>
          <small>of {privateCount} hidden</small>
        </span>
      </div>
      {privateCount > 0 ? (
        <div class="card-foot">
          <form method="post" action="/portal/settings/private" data-confirm={`Publish ${privateHidden} hidden private ${privateHidden === 1 ? "song" : "songs"}? Everyone using the app will see them.`}>
            <Csrf value={csrf} />
            <input type="hidden" name="action" value="publish" />
            <button class="btn" type="submit" disabled={privateHidden === 0}>
              <Icon name="eye" />
              Publish all private songs
            </button>
          </form>
          <form method="post" action="/portal/settings/private" data-confirm={`Hide ${privatePublished} published private ${privatePublished === 1 ? "song" : "songs"} from everyone but staff?`}>
            <Csrf value={csrf} />
            <input type="hidden" name="action" value="hide" />
            <button class="btn primary" type="submit" disabled={privatePublished === 0}>
              <Icon name="eyeoff" />
              Hide all private songs
            </button>
          </form>
        </div>
      ) : null}
    </section>

    <form class="card settings" method="post" action="/portal/seed">
      <Csrf value={csrf} />
      <div class="setting">
        <span>
          <h3>Sample songs</h3>
          <p>Add six public-domain songs (Amazing Grace, Doxology and more). Songs already in the library are skipped.</p>
        </span>
        <button class="btn" type="submit">
          Load sample songs
        </button>
      </div>
    </form>
  </>
);

/** Shown once to the owner after creating an admin's portal sign-in link. */
export const PortalLoginLinkPage: FC<{ who: string; link: string; expiresAt: number; replacing: boolean }> = ({ who, link, expiresAt, replacing }) => (
  <>
    <PageHead
      title="Portal sign-in link"
      crumb={{ href: "/portal/users?kind=staff", label: "Users" }}
      sub={`Send this link to ${who} privately. It works once, until ${new Date(expiresAt).toUTCString().replace(" GMT", " UTC")}.`}
    />
    <section class="card card-pad link-card">
      <h2>One-time link</h2>
      <p>
        Opening it lets them choose a sign-in name and password, then signs them in.
        {replacing ? " Their current password keeps working until they use it." : ""} Making another link cancels this one.
      </p>
      <div class="linkline">
        <label class="sr-only" for="setup-link">
          Setup link
        </label>
        <input class="input" id="setup-link" readonly value={link} />
        <button class="btn sm" type="button" data-copy="#setup-link">
          Copy
        </button>
      </div>
      <p class="faint small-note">The link is not stored anywhere readable: if it is lost, make a new one.</p>
    </section>
  </>
);
