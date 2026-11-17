const CHORD_TOKEN =
  /^\(?[A-G][#b]?(?:maj|min|m|dim|aug|sus|add|[#b+°ø]|\d)*(?:\([^)]*\))?(?:\/[A-G][#b]?)?\)?$|^N\.?C\.?$/;
const HEADER_RE = /^\s*\[([^\]]+)\]\s*$/;

function isHeader(line: string): boolean {
  const m = HEADER_RE.exec(line);
  return m !== null && !isChordLine(m[1]);
}

export const MAX_LINES = 5_000;
export const MAX_LINE_LEN = 2_000;
export const MAX_CHORDS_PER_LINE = 64;

/** Expand tabs, truncating to MAX_LINE_LEN. */
function expandTabs(line: string): string {
  if (!line.includes("\t")) return line.length > MAX_LINE_LEN ? line.slice(0, MAX_LINE_LEN) : line;
  const parts: string[] = [];
  let len = 0;
  for (const ch of line) {
    if (len >= MAX_LINE_LEN) break;
    if (ch === "\t") {
      const pad = 8 - (len % 8);
      parts.push(" ".repeat(pad));
      len += pad;
    } else {
      parts.push(ch);
      len += ch.length;
    }
  }
  return parts.join("").slice(0, MAX_LINE_LEN);
}

function isChordLine(line: string): boolean {
  const tokens = line.trim().split(/\s+/).filter(Boolean);
  return tokens.length > 0 && tokens.every((t) => CHORD_TOKEN.test(t));
}

function chordTokens(line: string): { col: number; name: string }[] {
  const out: { col: number; name: string }[] = [];
  for (const m of line.matchAll(/\S+/g)) {
    out.push({ col: m.index, name: m[0].replace(/^\(|\)$/g, "") });
    if (out.length >= MAX_CHORDS_PER_LINE) break;
  }
  return out;
}

function merge(chordLine: string, lyric: string): string {
  const tokens = chordTokens(chordLine);
  const last = tokens.length ? tokens[tokens.length - 1].col : 0;
  const base = lyric.length < last ? lyric.padEnd(last, " ") : lyric;
  const pieces: string[] = [];
  let pos = 0;
  for (const { col, name } of tokens) {
    pieces.push(base.slice(pos, col), `[${name}]`);
    pos = col;
  }
  pieces.push(base.slice(pos));
  return pieces.join("");
}

function chordOnly(chordLine: string): string {
  return chordTokens(chordLine).map((t) => `[${t.name}]`).join(" ");
}

/** Convert a chords-above-lyrics paste into ChordPro. Deterministic. */
export function chordsOverLyricsToChordPro(
  text: string,
  meta: { title?: string; artist?: string } = {},
): string {
  const out: string[] = [];
  if (meta.title) out.push(`{title: ${meta.title}}`);
  if (meta.artist) out.push(`{artist: ${meta.artist}}`);
  if (out.length) out.push("");

  const lines = text.split(/\r\n|\r|\n/, MAX_LINES).map((l) => expandTabs(l).replace(/\s+$/, ""));
  let open: string | null = null;

  const close = () => {
    if (!open) return;
    while (out.length && out[out.length - 1] === "") out.pop();
    out.push(`{end_of_${open}}`, "");
    open = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const hm = isHeader(line) ? HEADER_RE.exec(line) : null;
    if (hm) {
      close();
      const header = hm[1].trim();
      const lower = header.toLowerCase();
      const type = lower.startsWith("chorus") ? "chorus" : lower.startsWith("bridge") ? "bridge" : "verse";
      const plain = lower === type;
      out.push(plain ? `{start_of_${type}}` : `{start_of_${type}: ${header}}`);
      open = type;
      continue;
    }
    if (line.trim() === "") {
      if (out.length && out[out.length - 1] !== "") out.push("");
      continue;
    }
    if (isChordLine(line)) {
      const next = lines[i + 1];
      if (next !== undefined && next.trim() !== "" && !isChordLine(next) && !isHeader(next)) {
        out.push(merge(line, next));
        i++;
      } else {
        out.push(chordOnly(line));
      }
      continue;
    }
    out.push(line);
  }
  close();
  while (out.length && out[out.length - 1] === "") out.pop();
  return out.join("\n") + "\n";
}
