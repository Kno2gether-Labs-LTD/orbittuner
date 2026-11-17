import type { FC } from "hono/jsx";
import { Csrf, Icon, PageHead, ago } from "./Layout";
import { Sheet } from "./Sheet";

export type ReviewTab = "review" | "flagged" | "hidden";
export const REVIEW_TABS: ReviewTab[] = ["review", "flagged", "hidden"];
export type ReviewCounts = Record<ReviewTab, number>;

/** `by`: the contributor's email, a staff name, or null for an anonymous member. */
export type ReviewSong = { id: string; title: string; artist: string | null; chordpro: string; source: string; by: string | null; updated_at: number };
export type FlaggedSong = {
  id: string;
  title: string;
  artist: string | null;
  status: "published" | "hidden";
  flags: number;
  reports: number;
  last_at: number;
  reasons: { reason: string; n: number }[];
  notes: string[];
};
export type HiddenSong = { id: string; title: string; artist: string | null; updated_at: number; moderated_at: number | null; flags: number };

export const REASON_LABEL: Record<string, string> = {
  wrong_chords: "Wrong chords",
  wrong_lyrics: "Wrong lyrics",
  not_a_song: "Not a song",
  other: "Other",
};

const TAB_LABEL: Record<ReviewTab, string> = { review: "In review", flagged: "Flagged", hidden: "Hidden" };

const back = (tab: ReviewTab) => `/portal/review?tab=${tab}`;

const StatusForm: FC<{ csrf: string; id: string; status: "published" | "hidden"; label: string; kind: string; tab: ReviewTab; icon?: string }> = ({
  csrf, id, status, label, kind, tab, icon,
}) => (
  <form method="post" action={`/portal/songs/${encodeURIComponent(id)}/status`}>
    <Csrf value={csrf} />
    <input type="hidden" name="status" value={status} />
    <input type="hidden" name="back" value={back(tab)} />
    <button class={`btn sm ${kind}`} type="submit">
      {icon ? <Icon name={icon} /> : null}
      {label}
    </button>
  </form>
);

const DeleteForm: FC<{ csrf: string; id: string; title: string; tab: ReviewTab }> = ({ csrf, id, title, tab }) => (
  <form method="post" action={`/portal/songs/${encodeURIComponent(id)}/delete`} data-confirm={`Delete “${title}” for good? This cannot be undone.`}>
    <Csrf value={csrf} />
    <input type="hidden" name="back" value={back(tab)} />
    <button class="btn sm danger" type="submit">
      Delete
    </button>
  </form>
);

const EditLink: FC<{ id: string; label?: string }> = ({ id, label = "Edit" }) => (
  <a class="btn sm" href={`/portal/songs/${encodeURIComponent(id)}`}>
    {label}
  </a>
);

const Empty: FC<{ title: string; text: string }> = ({ title, text }) => (
  <div class="card empty slim">
    <Icon name="check" />
    <h3>{title}</h3>
    <p>{text}</p>
  </div>
);

export const ReviewPage: FC<{
  csrf: string;
  tab: ReviewTab;
  counts: ReviewCounts;
  songs?: ReviewSong[];
  flagged?: FlaggedSong[];
  hidden?: HiddenSong[];
}> = ({ csrf, tab, counts, songs = [], flagged = [], hidden = [] }) => (
  <>
    <PageHead
      title="Review"
      sub="New songs that missed the publishing rules, songs people flagged, and everything hidden. Every decision here pins the song: flags no longer hide it on their own."
    />
    <nav class="seg tabs" aria-label="Review queues">
      {REVIEW_TABS.map((t) => (
        <a href={back(t)} aria-current={t === tab ? "true" : undefined}>
          {TAB_LABEL[t]}
          <span class={t === "flagged" && counts.flagged > 0 ? "n hot" : "n"}>{counts[t]}</span>
        </a>
      ))}
    </nav>

    {tab === "review" ? (
      songs.length === 0 ? (
        <Empty title="Nothing waiting" text="Contributions that need a second look land here: low-confidence AI imports, anonymous additions and near-duplicates." />
      ) : (
        <ul class="review-list">
          {songs.map((s) => (
            <li class="card review-item">
              <div>
                <h3>
                  <a href={`/portal/songs/${encodeURIComponent(s.id)}`}>{s.title}</a>
                </h3>
                <p class="sub">
                  {s.artist || "Unknown artist"} · added by {s.by ?? "an anonymous member"}
                  {s.source === "import" ? " (import)" : s.source === "web" ? " (web)" : ""} · {ago(s.updated_at)}
                </p>
                <div class="snip" aria-hidden="true">
                  <Sheet src={s.chordpro} head={false} maxSections={1} />
                </div>
              </div>
              <div class="acts">
                <StatusForm csrf={csrf} id={s.id} status="published" label="Publish" kind="primary" tab="review" />
                <EditLink id={s.id} label="Edit first" />
                <StatusForm csrf={csrf} id={s.id} status="hidden" label="Hide" kind="quiet" tab="review" />
                <DeleteForm csrf={csrf} id={s.id} title={s.title} tab="review" />
              </div>
            </li>
          ))}
        </ul>
      )
    ) : null}

    {tab === "flagged" ? (
      flagged.length === 0 ? (
        <Empty title="No open flags" text="When people flag wrong chords, wrong lyrics or something that is not a song, it shows up here." />
      ) : (
        <ul class="review-list">
          {flagged.map((f) => {
            const n = f.flags || f.reports;
            const noun = f.flags ? (n === 1 ? "flag" : "flags") : n === 1 ? "report" : "reports";
            return (
              <li class="card review-item flagged">
                <div>
                  <div class="flag-head">
                    <span class="flag-n" aria-hidden="true">
                      <Icon name="flag" />
                      {n}
                    </span>
                    <div>
                      <h3>
                        <a href={`/portal/songs/${encodeURIComponent(f.id)}`}>{f.title}</a>
                      </h3>
                      <p class="sub">
                        {f.artist || "Unknown artist"} · {n} {noun} · last {ago(f.last_at)}
                        {f.status === "hidden" ? " · hidden automatically" : " · still published"}
                      </p>
                    </div>
                  </div>
                  {f.reasons.length ? (
                    <div class="reasons">
                      {f.reasons.map((r) => (
                        <span class="chip reason">
                          {REASON_LABEL[r.reason] ?? r.reason} <b>{r.n}</b>
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {f.notes.map((note) => (
                    <p class="quote">“{note}”</p>
                  ))}
                </div>
                <div class="acts">
                  {f.status === "hidden" ? (
                    <StatusForm csrf={csrf} id={f.id} status="published" label="Publish again" kind="primary" tab="flagged" />
                  ) : (
                    <StatusForm csrf={csrf} id={f.id} status="published" label="Keep published" kind="primary" tab="flagged" />
                  )}
                  <EditLink id={f.id} label="Fix the song" />
                  {f.status === "hidden" ? (
                    <StatusForm csrf={csrf} id={f.id} status="hidden" label="Keep hidden" kind="quiet" tab="flagged" />
                  ) : (
                    <StatusForm csrf={csrf} id={f.id} status="hidden" label="Hide" kind="quiet" tab="flagged" />
                  )}
                  {f.status === "hidden" ? <DeleteForm csrf={csrf} id={f.id} title={f.title} tab="flagged" /> : null}
                </div>
              </li>
            );
          })}
        </ul>
      )
    ) : null}

    {tab === "hidden" ? (
      hidden.length === 0 ? (
        <Empty title="Nothing hidden" text="Songs you hide, or that flags hide, wait here. Only staff and the person who added them can see them." />
      ) : (
        <div class="card">
          <ul class="hidden-list">
            {hidden.map((h) => (
              <li>
                <div class="t">
                  <a href={`/portal/songs/${encodeURIComponent(h.id)}`}>
                    <b>{h.title}</b>
                  </a>
                  <small>
                    {h.artist || "Unknown artist"} · {h.moderated_at ? `hidden by staff ${ago(h.moderated_at)}` : `hidden by flags ${ago(h.updated_at)}`}
                    {h.flags ? ` · ${h.flags} ${h.flags === 1 ? "flag" : "flags"}` : ""}
                  </small>
                </div>
                <div class="acts">
                  <StatusForm csrf={csrf} id={h.id} status="published" label="Publish" kind="" tab="hidden" />
                  <DeleteForm csrf={csrf} id={h.id} title={h.title} tab="hidden" />
                </div>
              </li>
            ))}
          </ul>
        </div>
      )
    ) : null}
  </>
);
