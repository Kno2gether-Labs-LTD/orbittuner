import type { Child, FC } from "hono/jsx";
import type { Facets, Song } from "../songs/repo";
import { Csrf, Icon, Notice, PageHead, ago } from "./Layout";
import { languageName } from "./languages";

export type SongsQuery = { q: string; lang: string; cat: string; status: "" | Song["status"]; page: number };

function href(q: SongsQuery, patch: Partial<SongsQuery>): string {
  const m = { ...q, ...patch };
  const p = new URLSearchParams();
  if (m.q) p.set("q", m.q);
  if (m.lang) p.set("lang", m.lang);
  if (m.cat) p.set("cat", m.cat);
  if (m.status) p.set("status", m.status);
  if (m.page) p.set("page", String(m.page));
  const s = p.toString();
  return s ? `/portal?${s}` : "/portal";
}

const STATUS_TABS: { v: SongsQuery["status"]; label: string }[] = [
  { v: "", label: "All" },
  { v: "published", label: "Published" },
  { v: "review", label: "In review" },
  { v: "hidden", label: "Hidden" },
];

export const STATUS_LABEL: Record<Song["status"], string> = {
  published: "Published",
  review: "In review",
  hidden: "Hidden",
};

export const SongsPage: FC<{
  query: SongsQuery;
  items: Song[];
  total: number;
  nextPage: number | null;
  facets: Facets;
  libraryTotal: number;
  csrf: string;
  seeded?: { added: number; present: number; failed: number };
  /** Today's numbers, shown under the heading. */
  stats?: Child;
}> = ({ query, items, total, nextPage, facets, libraryTotal, csrf, seeded, stats }) => {
  const filtered = !!(query.q || query.lang || query.cat || query.status);
  const from = query.page * 25 + 1;
  return (
    <>
      {seeded === undefined ? null : (
        <Notice kind={seeded.failed > 0 ? "err" : undefined}>
          {`${seeded.added} added, ${seeded.present} already present, ${seeded.failed} failed.`}
        </Notice>
      )}
      <PageHead
        title="Library"
        sub={
          libraryTotal === 0
            ? "Every song people can open in the app."
            : `${libraryTotal} ${libraryTotal === 1 ? "song" : "songs"} across ${facets.languages.length} ${
                facets.languages.length === 1 ? "language" : "languages"
              }.`
        }
        actions={
          <a class="btn primary" href="/portal/songs/new">
            <Icon name="plus" />
            New song
          </a>
        }
      />
      {stats}
      <form class="filters" method="get" action="/portal" role="search">
        <label class="search">
          <span class="sr-only">Search songs</span>
          <Icon name="search" />
          <input class="input" type="search" name="q" value={query.q} placeholder="Search titles, artists, lyrics" maxlength={200} />
        </label>
        <label>
          <span class="sr-only">Language</span>
          <select class="input" name="lang" data-autosubmit>
            <option value="">All languages</option>
            {facets.languages.map((l) => (
              <option value={l.code} selected={l.code === query.lang}>
                {languageName(l.code)} ({l.count})
              </option>
            ))}
          </select>
        </label>
        <label>
          <span class="sr-only">Category</span>
          <select class="input" name="cat" data-autosubmit>
            <option value="">All categories</option>
            {facets.categories.map((c) => (
              <option value={c.name} selected={c.name === query.cat}>
                {c.name} ({c.count})
              </option>
            ))}
          </select>
        </label>
        {query.status ? <input type="hidden" name="status" value={query.status} /> : null}
        <button class="btn sr-only" type="submit">
          Apply
        </button>
        <nav class="seg" aria-label="Status">
          {STATUS_TABS.map((t) => (
            <a href={href(query, { status: t.v, page: 0 })} aria-current={query.status === t.v ? "true" : undefined}>
              {t.label}
            </a>
          ))}
        </nav>
      </form>

      <div class="card">
        {items.length === 0 ? (
          <div class="empty">
            <svg viewBox="0 0 64 64" aria-hidden="true">
              <ellipse class="orb-ring" cx="32" cy="32" rx="26" ry="11" transform="rotate(-24 32 32)" />
              <circle class="orb-planet" cx="32" cy="32" r="10" />
              <circle class="orb-moon" cx="55.5" cy="22.5" r="4" />
            </svg>
            {filtered ? (
              <>
                <h3>Nothing matches</h3>
                <p>Try a shorter search, or clear the filters to see the whole library.</p>
                <a class="btn" href="/portal">
                  Clear filters
                </a>
              </>
            ) : (
              <>
                <h3>Your library is empty</h3>
                <p>Write a song in ChordPro, paste chords-over-lyrics text, or import a folder of files.</p>
                <div class="actions">
                  <a class="btn primary" href="/portal/songs/new">
                    <Icon name="plus" />
                    New song
                  </a>{" "}
                  <a class="btn" href="/portal/transfer">
                    Import songs
                  </a>
                </div>
                <form method="post" action="/portal/seed">
                  <Csrf value={csrf} />
                  <button class="btn" type="submit">
                    Load sample songs
                  </button>
                </form>
              </>
            )}
          </div>
        ) : (
          <>
            <div class="songs-head" aria-hidden="true">
              <span>Key</span>
              <span>Song</span>
              <span>Lang</span>
              <span>Categories</span>
              <span>Status</span>
              <span class="when">Updated</span>
            </div>
            <ol class="songs">
              {items.map((s) => (
                <li>
                  <a class="song" href={`/portal/songs/${encodeURIComponent(s.id)}`}>
                    <span class={s.originalKey ? "key" : "key none"}>{s.originalKey || "–"}</span>
                    <span class="t">
                      <b>{s.title}</b>
                      <small>{s.artist || "Unknown artist"}</small>
                    </span>
                    <span class="lang" title={languageName(s.language)}>
                      {s.language}
                    </span>
                    <span class="tags">
                      {s.categories.slice(0, 3).map((c) => (
                        <span class="tag">{c}</span>
                      ))}
                    </span>
                    <span class={`status ${s.status}`}>{STATUS_LABEL[s.status]}</span>
                    <span class="when">{ago(s.updatedAt)}</span>
                  </a>
                </li>
              ))}
            </ol>
            {query.page > 0 || nextPage != null ? (
              <div class="pager">
                <span>
                  {from}–{from + items.length - 1} of {total}
                </span>
                <div>
                  {query.page > 0 ? (
                    <a class="btn sm" href={href(query, { page: query.page - 1 })}>
                      Previous
                    </a>
                  ) : null}
                  {nextPage != null ? (
                    <a class="btn sm" href={href(query, { page: nextPage })}>
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
