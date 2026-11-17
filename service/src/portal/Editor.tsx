import type { FC } from "hono/jsx";
import type { Song } from "../songs/repo";
import { Csrf, Icon, Notice, PageHead, ago } from "./Layout";
import { Sheet } from "./Sheet";
import { LANGUAGES } from "./languages";
import { STATUS_LABEL } from "./Songs";

export type EditorValues = {
  chordpro: string;
  language: string;
  categories: string;
  difficulty: string;
  status: Song["status"];
};

export const DIFFICULTIES = ["easy", "medium", "hard"] as const;

const STARTER = `{title: }
{artist: }
{key: }

{start_of_verse: Verse 1}
[G]Write your [C]first line [D]here
{end_of_verse}

{start_of_chorus}
[C]Chords go in [G]brackets, right be[D]fore the syllable
{end_of_chorus}
`;

export const EditorPage: FC<{
  csrf: string;
  song?: Song;
  values?: EditorValues;
  error?: string;
  duplicateId?: string;
}> = ({ csrf, song, values, error, duplicateId }) => {
  const v: EditorValues = values ?? {
    chordpro: song?.chordpro ?? STARTER,
    language: song?.language ?? "en",
    categories: song?.categories.join(", ") ?? "",
    difficulty: song?.difficulty ?? "",
    status: song?.status ?? "published",
  };
  const action = song ? `/portal/songs/${encodeURIComponent(song.id)}` : "/portal/songs/new";
  const langs = LANGUAGES.some(([c]) => c === v.language) ? LANGUAGES : [...LANGUAGES, [v.language, v.language] as [string, string]];
  return (
    <>
      <form id="editor" method="post" action={action} data-editor>
        <Csrf value={csrf} />
        <PageHead
          crumb={{ href: "/portal", label: "Library" }}
          title={song ? song.title : "New song"}
          sub={
            song ? (
              <>
                Version {song.version} · saved {ago(song.updatedAt)} · {song.source === "owner" ? "written here" : song.source === "import" ? "imported" : "from the web"}
              </>
            ) : (
              "Write in ChordPro on the left. The preview shows it the way the app will."
            )
          }
          actions={
            <>
              <span class="savebar">
                <span class="dirty-dot" title="Unsaved changes"></span>
                <span>
                  <kbd data-mod>⌘</kbd> <kbd>S</kbd>
                </span>
              </span>
              <button class="btn primary" type="submit">
                {song ? "Save changes" : "Create song"}
              </button>
            </>
          }
        />
        {error ? (
          <Notice kind="err">
            {error}
            {duplicateId ? (
              <>
                {" "}
                <a href={`/portal/songs/${encodeURIComponent(duplicateId)}`}>Open the existing song</a>
              </>
            ) : null}
          </Notice>
        ) : null}

        <div class="card editor-meta">
          <label class="field">
            <span>Language</span>
            <select class="input" name="language">
              {langs.map(([code, name]) => (
                <option value={code} selected={code === v.language}>
                  {name}
                </option>
              ))}
            </select>
          </label>
          <label class="field">
            <span>Difficulty</span>
            <select class="input" name="difficulty">
              <option value="" selected={!v.difficulty}>
                Not set
              </option>
              {DIFFICULTIES.map((d) => (
                <option value={d} selected={d === v.difficulty}>
                  {d[0].toUpperCase() + d.slice(1)}
                </option>
              ))}
            </select>
          </label>
          <label class="field">
            <span>Status</span>
            <select class="input" name="status">
              {(["published", "review", "hidden"] as const).map((s) => (
                <option value={s} selected={s === v.status}>
                  {STATUS_LABEL[s]}
                </option>
              ))}
            </select>
          </label>
          <label class="field cats">
            <span>Categories</span>
            <input class="input" name="categories" value={v.categories} placeholder="hymn, wedding, campfire" maxlength={2000} />
          </label>
        </div>

        <div class="panes">
          <section class="card pane" aria-labelledby="src-h">
            <div class="pane-bar">
              <h2 id="src-h">ChordPro</h2>
              <span class="ai-out" data-ai-out aria-live="polite"></span>
              <button class="btn sm" type="button" data-open="paste">
                <Icon name="text" />
                Paste plain text
              </button>
              <button class="btn sm ai-btn" type="button" data-ai-convert title="Rewrite what is in the editor as clean ChordPro with AI">
                <Icon name="spark" />
                <span data-label>Convert with AI</span>
              </button>
            </div>
            <label class="sr-only" for="chordpro">
              ChordPro source
            </label>
            <textarea class="source" id="chordpro" name="chordpro" spellcheck={false} autocapitalize="off" autocomplete="off" required data-source>
              {"\n" + v.chordpro}
            </textarea>
          </section>
          <section class="card pane" aria-labelledby="pv-h">
            <div class="pane-bar">
              <h2 id="pv-h">Preview</h2>
              <span class="hint">As it reads on a phone</span>
            </div>
            <div class="preview" id="preview" aria-live="off" data-preview>
              <Sheet src={v.chordpro} />
            </div>
          </section>
        </div>
      </form>

      <dialog id="paste" aria-labelledby="paste-h">
        <form method="dialog" data-convert>
          <h2 id="paste-h">Paste plain text</h2>
          <p>Chords on their own line above the lyrics, the way most songbooks and websites print them. We convert it to ChordPro and replace what is in the editor.</p>
          <div class="duo">
            <label class="field">
              <span>Title</span>
              <input class="input" name="title" maxlength={300} />
            </label>
            <label class="field">
              <span>Artist</span>
              <input class="input" name="artist" maxlength={300} />
            </label>
          </div>
          <label class="field">
            <span class="sr-only">Song text</span>
            <textarea class="input" name="text" required placeholder={"G           C        G\nAmazing grace, how sweet the sound"}></textarea>
          </label>
          <div class="foot">
            <span class="err" data-error></span>
            <button class="btn quiet" type="button" data-close>
              Cancel
            </button>
            <button class="btn primary" type="submit">
              Convert
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
};
