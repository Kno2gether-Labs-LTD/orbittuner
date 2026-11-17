/**
 * Convert a fetched HTML page into plain text for the chord-sheet converter.
 *
 * A single-pass, linear-time tokenizer (no cross-document lazy regexes: input can be a hostile
 * 2 MB page full of unclosed tags). It drops script/style/nav/footer/header and friends, keeps
 * <pre> blocks verbatim (monospace chord sheets rely on column alignment), turns block elements
 * into newlines, decodes entities and allows at most 2 blank lines in a row.
 *
 * Linear-time guarantees: every indexOf either advances the cursor past what it scanned, or its
 * failure is cached (a tag with no closing tag is never searched for again, and the next '>' is
 * memoised), so the total work stays O(n).
 */

const NAMED: Record<string, string> = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", ensp: " ", emsp: " ", thinsp: " ",
  ndash: "–", mdash: "—", hellip: "…", lsquo: "‘", rsquo: "’",
  ldquo: "“", rdquo: "”", laquo: "«", raquo: "»", bull: "•", middot: "·",
  copy: "©", reg: "®", trade: "™", deg: "°", times: "×",
  sharp: "♯", flat: "♭", natural: "♮", shy: "",
};

export function decodeEntities(s: string): string {
  if (!s.includes("&")) return s;
  return s.replace(/&(#x[0-9a-f]{1,6}|#[0-9]{1,7}|[a-z][a-z0-9]{1,31});?/gi, (m, ref: string) => {
    if (ref[0] === "#") {
      const cp = ref[1] === "x" || ref[1] === "X" ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
      if (!Number.isFinite(cp) || cp <= 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return "�";
      return cp === 0xa0 ? " " : String.fromCodePoint(cp);
    }
    const v = NAMED[ref.toLowerCase()];
    return v === undefined ? m : v;
  });
}

/** Elements removed together with their content. */
const DROP = new Set([
  "script", "style", "noscript", "template", "svg", "iframe", "object", "head", "nav", "footer", "header",
  "form", "select", "button", "textarea",
]);
/** Elements whose start or end becomes a line break. */
const BLOCK = new Set([
  "address", "article", "aside", "blockquote", "br", "dd", "details", "div", "dl", "dt", "figcaption", "figure",
  "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "main", "ol", "p", "section", "summary", "table", "tbody", "td",
  "tfoot", "th", "thead", "tr", "ul",
]);

export type PageText = {
  /** Plain text of the page. */
  text: string;
  /** Number of non-empty <pre> blocks kept verbatim. */
  preBlocks: number;
  /** Text of the first <title>, or "" (the title is never part of `text`). */
  title: string;
};

/** Remove <!-- ... --> comments with indexOf (an unclosed comment runs to the end). Linear. */
function stripComments(s: string): string {
  let at = s.indexOf("<!--");
  if (at < 0) return s;
  const parts: string[] = [];
  let from = 0;
  while (at >= 0) {
    parts.push(s.slice(from, at));
    const end = s.indexOf("-->", at + 4);
    if (end < 0) return parts.join("");
    from = end + 3;
    at = s.indexOf("<!--", from);
  }
  parts.push(s.slice(from));
  return parts.join("");
}

/** Strip comments and tags from <pre> content; a "<" not starting a tag is kept as text. Linear. */
function preInner(inner: string): string {
  return decodeEntities(
    stripComments(inner)
      .replace(/<br\s*\/?>/gi, "\n")
      .replace(/<\/?[a-zA-Z][^<>]*>/g, ""),
  )
    .replace(/\r\n?/g, "\n")
    .replace(/^\n/, "")
    .trimEnd(); // not /\s+$/: that regex is quadratic on long inner whitespace runs
}

export function extractPageText(html: string): PageText {
  const src = html.replace(/\u0000/g, "");
  // ASCII-only lowercase keeps indexes aligned with `src` (String#toLowerCase can change length).
  const lower = src.replace(/[A-Z]+/g, (m) => m.toLowerCase());
  const n = src.length;
  const out: string[] = [];
  const pres: string[] = [];
  const noClose = new Set<string>();
  let title = "";
  let gtCache = -2; // last result of indexOf(">") (-1 = none left); -2 = not computed

  const nextGt = (from: number): number => {
    if (gtCache === -1) return -1;
    if (gtCache >= from) return gtCache;
    gtCache = src.indexOf(">", from);
    return gtCache;
  };

  /**
   * Find `</name` followed by a name boundary ('>', '/', whitespace or end) from `from`; returns its
   * index or -1 (failure cached per tag). The caller jumps past the match, so the scan stays linear.
   */
  const findClose = (name: string, from: number): number => {
    if (noClose.has(name)) return -1;
    const needle = `</${name}`;
    let at = lower.indexOf(needle, from);
    while (at >= 0) {
      const next = lower.charCodeAt(at + needle.length);
      if (Number.isNaN(next) || next === 0x3e || next === 0x2f || next === 0x20 || (next >= 0x09 && next <= 0x0d)) {
        return at;
      }
      at = lower.indexOf(needle, at + needle.length);
    }
    noClose.add(name);
    return -1;
  };

  const tagName = /[a-z][a-z0-9-]*/y;
  let i = 0;
  while (i < n) {
    const lt = src.indexOf("<", i);
    if (lt < 0) {
      out.push(decodeEntities(src.slice(i)));
      break;
    }
    if (lt > i) out.push(decodeEntities(src.slice(i, lt)));
    i = lt;

    if (src.startsWith("<!--", i)) {
      const end = src.indexOf("-->", i + 4);
      i = end < 0 ? n : end + 3;
      continue;
    }
    const c1 = lower.charCodeAt(i + 1);
    if (c1 === 0x21 /* ! */ || c1 === 0x3f /* ? */) {
      const gt = nextGt(i);
      i = gt < 0 ? n : gt + 1;
      continue;
    }
    const closing = c1 === 0x2f; /* / */
    tagName.lastIndex = i + (closing ? 2 : 1);
    const m = tagName.exec(lower);
    if (!m) {
      out.push("<"); // a literal "<", not a tag
      i += 1;
      continue;
    }
    const name = m[0];
    const gt = nextGt(tagName.lastIndex);
    if (gt < 0) {
      // Unterminated tag at the end of the document: drop the rest.
      break;
    }
    const afterTag = gt + 1;

    if (closing) {
      if (BLOCK.has(name) || name === "pre") out.push("\n");
      i = afterTag;
      continue;
    }

    if (name === "pre" || name === "title" || DROP.has(name)) {
      if (src.charCodeAt(gt - 1) === 0x2f /* "/>" */) {
        i = afterTag; // self-closing (e.g. <svg .../>): a void element with no content
        continue;
      }
      const close = findClose(name, afterTag);
      if (close < 0) {
        // No closing tag anywhere after this: drop only the open tag.
        if (name === "pre") out.push("\n");
        i = afterTag;
        continue;
      }
      const inner = src.slice(afterTag, close);
      const closeGt = nextGt(close);
      i = closeGt < 0 ? n : closeGt + 1;
      if (name === "pre") {
        const body = preInner(inner);
        if (body) {
          pres.push(body);
          out.push(`\n\u0000${pres.length - 1}\u0000\n`);
        }
      } else if (name === "title" && !title) {
        title = decodeEntities(inner.replace(/<[^<>]*>/g, "")).replace(/\s+/g, " ").trim();
      } else if (name === "head" && !title) {
        // <title> lives in <head>; capture it before the head is dropped.
        const t = inner.match(/<title\b[^<>]*>([^<]*)/i);
        if (t) title = decodeEntities(t[1]).replace(/\s+/g, " ").trim();
      }
      continue;
    }

    if (BLOCK.has(name)) out.push("\n");
    i = afterTag;
  }

  const text = out
    .join("")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/[ \t\f\v ]+/g, " ").trim())
    .join("\n")
    .replace(/\n{4,}/g, "\n\n\n")
    .trim()
    .replace(/\u0000(\d+)\u0000/g, (_m, k: string) => pres[Number(k)] ?? "");

  return { text, preBlocks: pres.length, title };
}

export function htmlToText(html: string): string {
  return extractPageText(html).text;
}
