export type Segment = { chord?: string; text: string };
export type Line =
  | { kind: "lyrics"; segments: Segment[] }
  | { kind: "comment"; text: string }
  | { kind: "tab"; text: string }
  | { kind: "directive"; name: string; value: string };
export type SectionType = "verse" | "chorus" | "bridge" | "tab" | "none";
export type Section = { type: SectionType; label?: string; lines: Line[] };
export type ChordProDoc = {
  meta: Record<string, string>;
  sections: Section[];
  chords: string[];
  lyricsText: string;
};

export const MAX_DOC_CHARS = 200_000;
export const MAX_LINE_CHARS = 2_000;
// Linear: no overlapping quantifiers; value is trimmed separately.
const DIRECTIVE_RE = /^\{\s*([A-Za-z_][\w-]*)(?:\s*:\s*|\s+)?([^}]*)\}$/;
const CHORD_RE = /\[([^\]]*)\]/g;

const ALIASES: Record<string, string> = { t: "title", st: "subtitle" };
const COMMENTS = new Set(["c", "comment", "ci", "comment_italic", "cb", "comment_box"]);
const META = new Set([
  "title", "subtitle", "artist", "composer", "lyricist", "album", "year",
  "key", "tempo", "time", "capo", "duration", "copyright",
]);
const SECTION_START: Record<string, SectionType> = {
  start_of_verse: "verse", sov: "verse",
  start_of_chorus: "chorus", soc: "chorus",
  start_of_bridge: "bridge", sob: "bridge",
  start_of_tab: "tab", sot: "tab",
};
const SECTION_END = new Set([
  "end_of_verse", "eov", "end_of_chorus", "eoc",
  "end_of_bridge", "eob", "end_of_tab", "eot",
]);

function parseLyricsLine(raw: string): Segment[] {
  const segments: Segment[] = [];
  let last = 0;
  let pending: string | undefined;
  for (const m of raw.matchAll(CHORD_RE)) {
    const text = raw.slice(last, m.index);
    if (pending !== undefined || text !== "") {
      segments.push(pending !== undefined ? { chord: pending, text } : { text });
    }
    pending = m[1].trim() || undefined;
    last = m.index + m[0].length;
  }
  const tail = raw.slice(last);
  if (pending !== undefined) segments.push({ chord: pending, text: tail });
  else if (tail !== "" || segments.length === 0) segments.push({ text: tail });
  return segments;
}

/** Line-based ChordPro parser. Never throws. */
export function parseChordPro(src: string): ChordProDoc {
  const meta: Record<string, string> = {};
  const sections: Section[] = [];
  const chords: string[] = [];
  const lyrics: string[] = [];
  let current: Section | null = null;

  const target = (): Section => {
    if (!current) {
      current = { type: "none", lines: [] };
      sections.push(current);
    }
    return current;
  };

  const capped = String(src ?? "").slice(0, MAX_DOC_CHARS);
  for (const rawLine of capped.split(/\r\n|\r|\n/)) {
    const raw = rawLine.length > MAX_LINE_CHARS ? rawLine.slice(0, MAX_LINE_CHARS) : rawLine;
    const inTab = current !== null && (current as Section).type === "tab";
    const trimmed = raw.trim();

    const dm =
      trimmed.startsWith("{") && trimmed.endsWith("}") ? DIRECTIVE_RE.exec(trimmed) : null;
    if (dm) {
      let name = dm[1].toLowerCase();
      const value = dm[2].trim();
      name = ALIASES[name] ?? name;
      if (name in SECTION_START) {
        const type = SECTION_START[name];
        current = { type, lines: [] };
        if (value) current.label = value;
        sections.push(current);
        continue;
      }
      if (SECTION_END.has(name)) {
        current = null;
        continue;
      }
      if (COMMENTS.has(name)) {
        target().lines.push({ kind: "comment", text: value });
        continue;
      }
      if (META.has(name)) {
        meta[name] = value;
        continue;
      }
      target().lines.push({ kind: "directive", name, value });
      continue;
    }

    if (inTab) {
      (current as unknown as Section).lines.push({ kind: "tab", text: raw });
      continue;
    }
    if (trimmed === "" || trimmed.startsWith("#")) continue;

    const segments = parseLyricsLine(raw);
    for (const s of segments) {
      if (s.chord && !chords.includes(s.chord)) chords.push(s.chord);
    }
    lyrics.push(segments.map((s) => s.text).join(""));
    target().lines.push({ kind: "lyrics", segments });
  }

  for (const sec of sections) {
    if (sec.type !== "tab") continue;
    while (sec.lines.length) {
      const l = sec.lines[sec.lines.length - 1];
      if (l.kind === "tab" && l.text.trim() === "") sec.lines.pop();
      else break;
    }
  }

  if (meta.subtitle && !meta.artist) meta.artist = meta.subtitle;
  return { meta, sections, chords, lyricsText: lyrics.join("\n") };
}

export function validateSong(
  src: string,
): { ok: true; doc: ChordProDoc } | { ok: false; error: "missing_title" | "empty" | "too_large" } {
  if (!src || src.trim() === "") return { ok: false, error: "empty" };
  if (src.length > MAX_DOC_CHARS) return { ok: false, error: "too_large" };
  const doc = parseChordPro(src);
  if (!doc.meta.title || doc.meta.title.trim() === "") return { ok: false, error: "missing_title" };
  return { ok: true, doc };
}
