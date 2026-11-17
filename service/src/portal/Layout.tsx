import type { Child, FC } from "hono/jsx";
import type { AuthUser } from "../auth/devices";
import css from "./portal.css";
import clientJs from "./portal.client.js";

/** Short content hash so asset URLs change whenever the file does (FNV-1a, 32 bit). */
function hash(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}

export const ASSETS = {
  css: { body: css, version: hash(css), type: "text/css; charset=utf-8" },
  js: { body: clientJs, version: hash(clientJs), type: "text/javascript; charset=utf-8" },
};

const FAVICON =
  "data:image/svg+xml," +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="9" fill="#0e0c1d"/><ellipse cx="16" cy="16" rx="11" ry="5.2" fill="none" stroke="#ff8a5c" stroke-width="1.6" transform="rotate(-28 16 16)"/><circle cx="16" cy="16" r="4.6" fill="#f2eee6"/><circle cx="25" cy="10.4" r="2.1" fill="#ffc48f"/></svg>`,
  );

/** The Orbit family mark: a planet with a tilted copper orbit and a moon. */
export const Mark: FC = () => (
  <svg viewBox="0 0 40 40" aria-hidden="true">
    <defs>
      <linearGradient id="og" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#ffc48f" />
        <stop offset=".55" stop-color="#ff8a5c" />
        <stop offset="1" stop-color="#e0455a" />
      </linearGradient>
    </defs>
    <rect width="40" height="40" rx="12" fill="#161329" />
    <ellipse cx="20" cy="20" rx="14" ry="6.4" fill="none" stroke="url(#og)" stroke-width="1.8" transform="rotate(-28 20 20)" />
    <circle cx="20" cy="20" r="6" fill="#f2eee6" />
    <circle cx="31.4" cy="13.2" r="2.6" fill="#ffc48f" />
  </svg>
);

// Line icons, 24px grid, drawn with currentColor.
const PATHS: Record<string, string> = {
  library: "M5 4h3v16H5zM10.5 4h3v16h-3zM16 5.2l2.9-.8 3.3 15.3-2.9.8z",
  review: "M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6-5.3-3-5.3 3 1.2-6L3.4 9.3l6-.7z",
  pair: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2h-2",
  devices: "M7 2.8h10a1.6 1.6 0 0 1 1.6 1.6v15.2a1.6 1.6 0 0 1-1.6 1.6H7a1.6 1.6 0 0 1-1.6-1.6V4.4A1.6 1.6 0 0 1 7 2.8zM10.5 18h3",
  people: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.8 20c.6-3.4 3.1-5.5 6.2-5.5s5.6 2.1 6.2 5.5M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14.8c1.8.7 3 2.5 3.4 5.2",
  transfer: "M7 4v13M3.5 13.5 7 17l3.5-3.5M17 20V7M13.5 10.5 17 7l3.5 3.5",
  settings: "M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4",
  signout: "M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 16l-4-4 4-4M6 12h10",
  plus: "M12 5v14M5 12h14",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4",
  back: "M15 5l-7 7 7 7",
  upload: "M12 16V4M7.5 8.5 12 4l4.5 4.5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3",
  download: "M12 4v12M7.5 11.5 12 16l4.5-4.5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3",
  archive: "M3.5 5h17v4h-17zM5 9v10h14V9M10 13h4",
  shield: "M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6z",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01",
  text: "M4 6h16M4 10h16M4 14h10M4 18h7",
  note: "M9 18V6l11-2v12M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
  check: "M5 12.5l4.5 4.5L19 7.5",
  spark: "M12 3.5l1.9 5.1 5.1 1.9-5.1 1.9L12 17.5l-1.9-5.1L5 10.5l5.1-1.9zM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z",
  key: "M14.5 3.5a6 6 0 1 1-4.9 9.5L3.5 19v2h3v-2h2v-2h2l1.6-1.6A6 6 0 0 1 14.5 3.5zM16.5 7.5h.01",
  link: "M10 14a4.5 4.5 0 0 0 6.4 0l3.2-3.2a4.5 4.5 0 0 0-6.4-6.4L12 5.6M14 10a4.5 4.5 0 0 0-6.4 0l-3.2 3.2a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2",
  up: "M6 15l6-6 6 6",
  down: "M6 9l6 6 6-6",
  gauge: "M4 17a8 8 0 1 1 16 0M12 17l3.5-5.5",
  flag: "M5 21V4.5M5 4.5c4.5-2.4 7.5 2.4 14 0v9c-6.5 2.4-9.5-2.4-14 0",
  eye: "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12zM12 14.8a2.8 2.8 0 1 0 0-5.6 2.8 2.8 0 0 0 0 5.6z",
  eyeoff: "M3.5 3.5l17 17M10 5.8c.6-.2 1.3-.3 2-.3 6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-2.7 3.5M6.6 6.9C4 8.6 2.5 12 2.5 12s3.5 6.5 9.5 6.5c1.6 0 3-.4 4.2-1",
  code: "M8.5 7 3.5 12l5 5M15.5 7l5 5-5 5M13.5 4.5l-3 15",
  chevron: "M9 6l6 6-6 6",
  trash: "M4.5 7h15M10 11v6M14 11v6M6 7l1 12.5a1.5 1.5 0 0 0 1.5 1.5h7a1.5 1.5 0 0 0 1.5-1.5L18 7M9.5 7V4.5h5V7",
};

export const Icon: FC<{ name: keyof typeof PATHS | string }> = ({ name }) => (
  <svg class="i" viewBox="0 0 24 24" aria-hidden="true">
    <path d={PATHS[name] ?? ""} />
  </svg>
);

export const initials = (name: string) =>
  name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w.slice(0, 1).toUpperCase())
    .join("") || "?";

const Head: FC<{ title: string; csrf?: string }> = ({ title, csrf }) => (
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width,initial-scale=1" />
    <meta name="color-scheme" content="dark light" />
    <meta name="theme-color" content="#0e0c1d" media="(prefers-color-scheme: dark)" />
    <meta name="theme-color" content="#f3f2f8" media="(prefers-color-scheme: light)" />
    {csrf ? <meta name="csrf" content={csrf} /> : null}
    <title>{title} · OrbitTuner</title>
    <link rel="icon" href={FAVICON} />
    <link rel="stylesheet" href={`/portal/assets/portal.css?v=${ASSETS.css.version}`} />
    <script src={`/portal/assets/portal.js?v=${ASSETS.js.version}`} defer></script>
  </head>
);

export type NavKey = "library" | "review" | "users" | "pair" | "devices" | "transfer" | "ai" | "settings" | "none";

export type ShellProps = {
  title: string;
  active: NavKey;
  user: AuthUser;
  csrf: string;
  reviewCount: number;
  children?: Child;
};

/** `owner: true` entries are owner-only pages; admins see the library, review, users and their developer device. */
const NAV: { key: NavKey; href: string; label: string; icon: string; owner?: true }[] = [
  { key: "library", href: "/portal", label: "Library", icon: "library" },
  { key: "review", href: "/portal/review", label: "Review", icon: "review" },
  { key: "users", href: "/portal/users", label: "Users", icon: "people" },
  { key: "pair", href: "/portal/pair", label: "Developer device", icon: "code" },
  { key: "devices", href: "/portal/devices", label: "Devices", icon: "devices", owner: true },
  { key: "transfer", href: "/portal/transfer", label: "Import & export", icon: "transfer", owner: true },
  { key: "ai", href: "/portal/ai", label: "AI & keys", icon: "spark", owner: true },
  { key: "settings", href: "/portal/settings", label: "Settings", icon: "settings", owner: true },
];

export const Shell: FC<ShellProps> = ({ title, active, user, csrf, reviewCount, children }) => (
  <html lang="en">
    <Head title={title} csrf={csrf} />
    <body>
      <div class="shell">
        <aside class="side">
          <a class="brand" href="/portal" aria-label="OrbitTuner library">
            <Mark />
            <span>
              <b>
                Orbit<span>Tuner</span>
              </b>
              <small>Song library</small>
            </span>
          </a>
          <nav class="nav" aria-label="Portal">
            {NAV.filter((n) => !n.owner || user.role === "owner").map((n) => (
              <>
                {n.key === "pair" ? <div class="nav-sep" role="presentation"></div> : null}
                <a href={n.href} aria-current={n.key === active ? "page" : undefined}>
                  <Icon name={n.icon} />
                  {n.label}
                  {n.key === "review" && reviewCount > 0 ? (
                    <span class="count" aria-label={`${reviewCount} waiting`}>
                      {reviewCount}
                    </span>
                  ) : null}
                </a>
              </>
            ))}
          </nav>
          <div class="who">
            <span class="av owner">{initials(user.name)}</span>
            <div>
              <b>{user.name}</b>
              <small>{user.role === "owner" ? "Owner" : "Admin"}</small>
            </div>
            <form method="post" action="/portal/logout">
              <input type="hidden" name="_csrf" value={csrf} />
              <button type="submit" title="Sign out" aria-label="Sign out">
                <Icon name="signout" />
              </button>
            </form>
          </div>
        </aside>
        <main class="main">
          <div class="wrap">{children}</div>
        </main>
      </div>
    </body>
  </html>
);

/** Centered single-card layout for setup and sign-in. */
export const AuthShell: FC<{ title: string; children?: Child }> = ({ title, children }) => (
  <html lang="en">
    <Head title={title} />
    <body>
      <main class="auth">
        <div class="auth-card">
          <div class="brand">
            <Mark />
            <b>
              Orbit<span>Tuner</span>
            </b>
          </div>
          {children}
        </div>
      </main>
    </body>
  </html>
);

export const PageHead: FC<{ title: string; sub?: Child; actions?: Child; crumb?: { href: string; label: string } }> = ({
  title,
  sub,
  actions,
  crumb,
}) => (
  <header class="head">
    <div>
      {crumb ? (
        <a class="crumb" href={crumb.href}>
          <Icon name="back" />
          {crumb.label}
        </a>
      ) : null}
      <h1>{title}</h1>
      {sub ? <p>{sub}</p> : null}
    </div>
    {actions ? <div class="actions">{actions}</div> : null}
  </header>
);

export const Notice: FC<{ kind?: "err" | "ok"; children?: Child }> = ({ kind, children }) => (
  <div class={kind === "err" ? "notice err" : "notice"} role={kind === "err" ? "alert" : "status"}>
    <Icon name={kind === "err" ? "info" : "check"} />
    <div>{children}</div>
  </div>
);

/** One figure in a stat strip: a label, a big number, an optional note and an optional meter. */
export type Stat = { key: string; label: string; value: number; note?: Child; of?: number; tone?: "warn" };

export const StatStrip: FC<{ stats: Stat[]; label: string }> = ({ stats, label }) => (
  <section class="stats" aria-label={label}>
    {stats.map((s) => (
      <div class={s.tone ? `stat ${s.tone}` : "stat"} data-stat={s.key}>
        <span class="stat-label">{s.label}</span>
        <span class="stat-num">
          <b>{s.value.toLocaleString("en")}</b>
          {s.of != null ? <small>of {s.of.toLocaleString("en")}</small> : null}
        </span>
        {s.of != null ? (
          <meter min={0} max={Math.max(1, s.of)} low={Math.max(1, s.of) * 0.7} high={Math.max(1, s.of) * 0.9} optimum={0} value={Math.min(s.value, Math.max(1, s.of))} aria-hidden="true"></meter>
        ) : null}
        {s.note ? <span class="stat-note">{s.note}</span> : null}
      </div>
    ))}
  </section>
);

export const Csrf: FC<{ value: string }> = ({ value }) => <input type="hidden" name="_csrf" value={value} />;

/** "3 min ago", "yesterday", "12 Mar 2026". */
export function ago(ms: number | null | undefined, now = Date.now()): string {
  if (!ms) return "never";
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  if (d === 1) return "yesterday";
  if (d < 7) return `${d} days ago`;
  return new Date(ms).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}
