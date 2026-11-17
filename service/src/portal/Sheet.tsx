import type { FC } from "hono/jsx";
import { parseChordPro, type Line, type Segment } from "../chordpro/parse";

const NBSP = " ";

/**
 * Split segments into word-sized cells so lines wrap between words, as the app does: the chord
 * stays on the first word of its segment and the following words get an empty chord row.
 * The browser preview (portal.client.js) mirrors this exactly.
 */
export function cells(segments: Segment[]): { chord: string; text: string }[] {
  const out: { chord: string; text: string }[] = [];
  for (const s of segments) {
    const words = s.text.match(/\S+\s*|\s+/g) ?? [""];
    words.forEach((w, i) => out.push({ chord: i === 0 && s.chord ? s.chord : "", text: w }));
  }
  return out;
}

const LineView: FC<{ line: Line }> = ({ line }) => {
  switch (line.kind) {
    case "comment":
      return <p class="cmt">{line.text}</p>;
    case "tab":
      return <div class="tab">{line.text || NBSP}</div>;
    case "directive":
      return null;
    case "lyrics": {
      if (!line.segments.some((s) => s.chord)) {
        return <div class="ln plain">{line.segments.map((s) => s.text).join("")}</div>;
      }
      return (
        <div class="ln">
          {cells(line.segments).map((c) => (
            <span class="sg">
              <span class="c">{c.chord || NBSP}</span>
              <span class="l">{c.text || NBSP}</span>
            </span>
          ))}
        </div>
      );
    }
  }
};

const LABELS = { verse: "Verse", chorus: "Chorus", bridge: "Bridge", tab: "Tab", none: "" } as const;

/** A ChordPro document rendered like the app's song view: chords above syllables. */
export const Sheet: FC<{ src: string; head?: boolean; maxSections?: number }> = ({ src, head = true, maxSections }) => {
  const doc = parseChordPro(src);
  const sections = maxSections ? doc.sections.slice(0, maxSections) : doc.sections;
  const facts = [
    ["Key", doc.meta.key],
    ["Capo", doc.meta.capo],
    ["Tempo", doc.meta.tempo],
    ["Time", doc.meta.time],
  ].filter((f): f is [string, string] => !!f[1]);
  return (
    <>
      {head ? (
        <header class="sheet-head">
          <h3>{doc.meta.title || "Untitled"}</h3>
          {doc.meta.artist ? <p>{doc.meta.artist}</p> : null}
          {facts.length ? (
            <div class="sheet-meta">
              {facts.map(([k, v]) => (
                <span>
                  {k}
                  <b>{v}</b>
                </span>
              ))}
            </div>
          ) : null}
        </header>
      ) : null}
      {sections.length === 0 ? <p class="sheet-empty">Lyrics and chords appear here as you type.</p> : null}
      {sections.map((sec) => {
        const label = sec.label ?? LABELS[sec.type];
        return (
          <section class={sec.type === "chorus" ? "sec chorus" : "sec"}>
            {label ? <div class="lab">{label}</div> : null}
            {sec.lines.map((l) => (
              <LineView line={l} />
            ))}
          </section>
        );
      })}
    </>
  );
};
