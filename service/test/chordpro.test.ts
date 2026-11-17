import { describe, expect, it } from "vitest";
import { parseChordPro, validateSong } from "../src/chordpro/parse";
import { chordsOverLyricsToChordPro } from "../src/chordpro/plaintext";
import amazingGrace from "./fixtures/chordpro/amazing-grace.cho?raw";
import unknownDirectives from "./fixtures/chordpro/unknown-directives.cho?raw";
import noTitle from "./fixtures/chordpro/no-title.cho?raw";
import overLyrics from "./fixtures/chordpro/chords-over-lyrics.txt?raw";
import overLyricsExpected from "./fixtures/chordpro/chords-over-lyrics.expected.cho?raw";

describe("parseChordPro", () => {
  it("parses amazing grace metadata, chords and lyrics", () => {
    const doc = parseChordPro(amazingGrace);
    expect(doc.meta.title).toBe("Amazing Grace");
    expect(doc.meta.artist).toBe("John Newton");
    expect(doc.meta.key).toBe("G");
    expect(doc.meta.tempo).toBe("80");
    expect(doc.meta.time).toBe("3/4");
    expect(doc.chords).toEqual(["G", "G7", "C", "Em", "D"]);
    expect(doc.lyricsText).toContain("Amazing grace how sweet the sound");
    expect(doc.lyricsText).not.toContain("[");
    expect(doc.sections.map((s) => [s.type, s.label])).toEqual([
      ["verse", "Verse 1"],
      ["verse", "Verse 2"],
    ]);
    expect(doc.sections[0].lines[0]).toEqual({
      kind: "lyrics",
      segments: [
        { chord: "G", text: "Amazing " },
        { chord: "G7", text: "grace how " },
        { chord: "C", text: "sweet the " },
        { chord: "G", text: "sound" },
      ],
    });
  });

  it("expands short directives and treats c as a comment", () => {
    const doc = parseChordPro("{t: Song}\n{st: Someone}\n{c: Intro}\n[C]la");
    expect(doc.meta).toEqual({ title: "Song", subtitle: "Someone", artist: "Someone" });
    expect(doc.sections[0].lines[0]).toEqual({ kind: "comment", text: "Intro" });
  });

  it("keeps explicit artist over subtitle", () => {
    const doc = parseChordPro("{title: A}\n{subtitle: B}\n{artist: C}");
    expect(doc.meta.artist).toBe("C");
  });

  it("keeps unknown directives and runs an unclosed tab to end of file", () => {
    const doc = parseChordPro(unknownDirectives);
    const all = doc.sections.flatMap((s) => s.lines);
    expect(all).toContainEqual({ kind: "directive", name: "x_custom", value: "foo" });
    expect(all).toContainEqual({ kind: "comment", text: "Intro" });
    const tab = doc.sections[doc.sections.length - 1];
    expect(tab.type).toBe("tab");
    expect(tab.lines).toHaveLength(4);
    expect(tab.lines.every((l) => l.kind === "tab")).toBe(true);
    expect(doc.meta.x_custom).toBeUndefined();
  });

  it("parses a pathological long line quickly (no ReDoS)", () => {
    for (const src of ["{a" + " ".repeat(50000) + "x", "{a" + " ".repeat(50000) + "x}", "{" + " ".repeat(50000), "[" + "A".repeat(50000)]) {
      const t = performance.now();
      expect(() => parseChordPro(src)).not.toThrow();
      expect(performance.now() - t).toBeLessThan(50);
    }
  });

  it("truncates lines over 2000 chars and caps documents", () => {
    const doc = parseChordPro("{title: X}\n" + "a".repeat(5000));
    expect(doc.lyricsText.length).toBe(2000);
    expect(() => parseChordPro("a\n".repeat(200000))).not.toThrow();
  });

  it("skips # comment lines and blank lines", () => {
    const doc = parseChordPro("{title: X}\n# note\n\n[C]hi");
    expect(doc.lyricsText).toBe("hi");
    expect(doc.sections[0].lines).toHaveLength(1);
  });

  it("treats an unclosed start_of_verse as running to end of file", () => {
    const doc = parseChordPro("{title: X}\n{start_of_verse: V}\n[C]one\n[G]two");
    expect(doc.sections).toHaveLength(1);
    expect(doc.sections[0]).toMatchObject({ type: "verse", label: "V" });
    expect(doc.sections[0].lines).toHaveLength(2);
  });

  it("never throws on garbage", () => {
    for (const s of ["{", "}{", "[", "[]", "{start_of_chorus", "{end_of_verse}", "]]][[[", "\u0000\n\n"]) {
      expect(() => parseChordPro(s)).not.toThrow();
    }
  });

  it("handles chords at end of line and CRLF", () => {
    const doc = parseChordPro("{title: X}\r\nHello [C]\r\n");
    expect(doc.sections[0].lines[0]).toEqual({
      kind: "lyrics",
      segments: [{ text: "Hello " }, { chord: "C", text: "" }],
    });
    expect(doc.lyricsText).toBe("Hello ");
  });
});

describe("validateSong", () => {
  it("accepts a song with a title", () => {
    const r = validateSong(unknownDirectives);
    expect(r.ok).toBe(true);
  });
  it("rejects a song without title", () => {
    expect(validateSong(noTitle)).toEqual({ ok: false, error: "missing_title" });
  });
  it("rejects documents over 200,000 chars", () => {
    expect(validateSong("{title: X}\n" + "a".repeat(200001))).toEqual({ ok: false, error: "too_large" });
  });
  it("rejects empty input", () => {
    expect(validateSong("")).toEqual({ ok: false, error: "empty" });
    expect(validateSong("  \n ")).toEqual({ ok: false, error: "empty" });
  });
});

describe("chordsOverLyricsToChordPro", () => {
  it("converts the fixture exactly", () => {
    const out = chordsOverLyricsToChordPro(overLyrics, {
      title: "Elm Gardens",
      artist: "Sample Writer",
    });
    expect(out).toBe(overLyricsExpected);
  });

  it("pads short lyric lines and handles chord-only lines", () => {
    const out = chordsOverLyricsToChordPro("C        G\nHi\n\nD  A\n", {});
    expect(out).toBe("[C]Hi" + " ".repeat(7) + "[G]\n\n[D] [A]\n");
  });

  it("recognises extended chord qualities as chord lines", () => {
    const out2 = chordsOverLyricsToChordPro("Am7b5 D7sus4 E7#9 Cmaj9 Gadd9 Bdim7 Caug C+ C° Dø7\nlyrics here and some more text to carry them\n", {});
    expect(out2.startsWith("[Am7b5]lyrics")).toBe(true);
    expect(out2).toContain("[C+]");
    expect(out2).toContain("[Dø7]");
  });

  it("does not treat ordinary sentences as chord lines", () => {
    expect(chordsOverLyricsToChordPro("I am the walrus\nA man a plan\n", {})).toBe("I am the walrus\nA man a plan\n");
  });

  it("does not treat bracketed chords as section headers", () => {
    expect(chordsOverLyricsToChordPro("[Am]\n[C#m7]\n[Am] [G]hello\n", {})).toBe("[Am]\n[C#m7]\n[Am] [G]hello\n");
  });

  it("emits a chord-only line before a header", () => {
    expect(chordsOverLyricsToChordPro("C G\n[Verse]\nla\n", {})).toBe(
      "[C] [G]\n{start_of_verse}\nla\n{end_of_verse}\n",
    );
  });

  it("handles two consecutive chord lines", () => {
    expect(chordsOverLyricsToChordPro("C G\nD  A\nla\n", {})).toBe("[C] [G]\n[D]la [A]\n");
  });

  it("expands tabs (stop 8) before aligning columns", () => {
    expect(chordsOverLyricsToChordPro("C\tG\nhello world foo\n", {})).toBe("[C]hello wo[G]rld foo\n");
  });
});
