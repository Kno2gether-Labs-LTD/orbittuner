import type { FC } from "hono/jsx";
import type { ImportResult } from "../transfer/zip";
import type { JobView } from "../ai/jobs";
import { Csrf, Icon, Notice, PageHead } from "./Layout";

export const IMPORT_ACCEPT = ".cho,.chordpro,.chopro,.crd,.pro,.txt,.zip";

export const ImportSummary: FC<{ result: ImportResult }> = ({ result }) => (
  <div class="summary" role="status">
    <div class="nums">
      <div class="ok">
        <b>{result.added}</b> added
      </div>
      <div>
        <b>{result.duplicates.length}</b> already in the library
      </div>
      <div class={result.failed.length ? "bad" : ""}>
        <b>{result.failed.length}</b> could not be read
      </div>
    </div>
    {result.failed.length ? (
      <details open={result.failed.length <= 5}>
        <summary>Files that failed</summary>
        <ul>
          {result.failed.slice(0, 200).map((f) => (
            <li>
              <span>{f.name}</span>
              <code>{f.error}</code>
            </li>
          ))}
        </ul>
      </details>
    ) : null}
    {result.duplicates.length ? (
      <details>
        <summary>Skipped as duplicates</summary>
        <ul>
          {result.duplicates.slice(0, 200).map((d) => (
            <li>
              <span>{d}</span>
            </li>
          ))}
        </ul>
      </details>
    ) : null}
  </div>
);

export const ITEM_LABEL: Record<JobView["items"][number]["status"], string> = {
  queued: "Waiting",
  done: "Added",
  duplicate: "Already in library",
  review: "Needs review",
  failed: "Failed",
  limit_reached: "Daily limit reached",
};

/** Live link-import progress. The page script re-renders this from GET /v1/import/jobs/:id. */
export const JobPanel: FC<{ job: JobView }> = ({ job }) => {
  const pct = job.total ? Math.round((job.done / job.total) * 100) : 100;
  return (
    <div class="job" data-job={job.id} data-job-status={job.status} aria-live="polite">
      <div class="job-head">
        <b data-job-count>
          {job.done} of {job.total} done
        </b>
        <span class={`status ${job.status === "done" ? "published" : "review"}`} data-job-state>
          {job.status === "done" ? "Finished" : "Working"}
        </span>
      </div>
      <progress max={100} value={pct} data-job-bar>
        {pct}%
      </progress>
      <ol class="job-items" data-job-items>
        {job.items.map((it) => (
          <li class={`job-item ${it.status}`}>
            <span class="job-url" title={it.url}>
              {it.url}
            </span>
            <span class="job-st">
              {ITEM_LABEL[it.status]}
              {it.error && it.status !== "done" ? <code>{it.error}</code> : null}
            </span>
            {it.songId ? (
              <a class="btn sm quiet" href={`/portal/songs/${encodeURIComponent(it.songId)}`}>
                Open
              </a>
            ) : (
              <span></span>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
};

export const TransferPage: FC<{
  csrf: string;
  songCount: number;
  maxSongs: number;
  result?: ImportResult;
  error?: string;
  job?: JobView;
  linksError?: string;
  links?: string;
}> = ({ csrf, songCount, maxSongs, result, error, job, linksError, links }) => (
  <>
    <PageHead title="Import & export" sub="Bring songs in from ChordPro files or ZIP archives, and take the whole library with you." />
    <div class="transfer">
      <div class="stack">
      <section class="card card-pad">
        <h2>Import songs</h2>
        <p>
          ChordPro files (.cho, .chordpro, .pro), plain chords-over-lyrics .txt, or ZIP archives of them. Songs already in the library are skipped. Up to{" "}
          {maxSongs} songs per archive; pick as many loose files as you like.
        </p>
        <form method="post" action="/portal/transfer" enctype="multipart/form-data" data-import data-max={String(maxSongs)}>
          <Csrf value={csrf} />
          <label class="drop" data-drop>
            <Icon name="upload" />
            <b>Drop files here</b>
            <span>or click to choose</span>
            <input type="file" name="files" multiple accept={IMPORT_ACCEPT} required data-files />
          </label>
          <p class="picked" data-picked aria-live="polite"></p>
          <label class="inline-field">
            <span>AI for text files</span>
            <select class="input" name="ai" data-ai-mode>
              <option value="auto" selected>
                Auto (when chords look unclear)
              </option>
              <option value="on">Always</option>
              <option value="off">Never</option>
            </select>
          </label>
          <div class="import-go">
            <button class="btn primary" type="submit" data-go>
              Import
            </button>
            <div class="progress" aria-hidden="true">
              <i></i>
            </div>
          </div>
        </form>
        <div data-result aria-live="polite">
          {error ? <Notice kind="err">{error}</Notice> : null}
          {result ? <ImportSummary result={result} /> : null}
        </div>
      </section>
      <section class="card card-pad" id="links">
        <h2>Import from links</h2>
        <p>One song page per line, up to 200. Each page is read and converted by AI in the background; you can leave this page while it runs.</p>
        <form method="post" action="/portal/transfer/links" data-links>
          <Csrf value={csrf} />
          <label class="sr-only" for="urls">
            Links, one per line
          </label>
          <textarea class="input links-input" id="urls" name="urls" required rows={6} spellcheck={false} autocomplete="off" placeholder={"https://tabs.example.com/amazing-grace\nhttps://chords.example.org/doxology"}>
            {"\n" + (links ?? "")}
          </textarea>
          <div class="import-go">
            <button class="btn primary" type="submit" data-links-go>
              <Icon name="link" />
              Import links
            </button>
            <span class="picked" data-links-count aria-live="polite"></span>
          </div>
        </form>
        <div data-links-out>
          {linksError ? <Notice kind="err">{linksError}</Notice> : null}
          {job ? <JobPanel job={job} /> : null}
        </div>
      </section>
      </div>
      <section class="card">
        <div class="card-pad">
          <h2>Export</h2>
          <p>
            {songCount} {songCount === 1 ? "song" : "songs"} in the library.
          </p>
        </div>
        <a class="dl" href="/portal/export.zip" download>
          <span class="ic">
            <Icon name="archive" />
          </span>
          <div>
            <b>All songs as ZIP</b>
            <small>One .cho file per song, readable by any ChordPro app.</small>
          </div>
          <Icon name="download" />
        </a>
        <a class="dl" href="/portal/backup.json" download>
          <span class="ic">
            <Icon name="shield" />
          </span>
          <div>
            <b>Full backup (JSON)</b>
            <small>Every song with your playlists and favourites. Keep it somewhere safe.</small>
          </div>
          <Icon name="download" />
        </a>
      </section>
    </div>
  </>
);
