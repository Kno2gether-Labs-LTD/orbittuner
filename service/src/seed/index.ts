import { zipSync, strToU8 } from "fflate";
import type { AuthUser } from "../auth/devices";
import { importFiles, type ImportResult } from "../transfer/zip";
import amazingGrace from "./songs/amazing-grace.cho";
import comeThouFount from "./songs/come-thou-fount.cho";
import doxology from "./songs/doxology.cho";
import itIsWell from "./songs/it-is-well.cho";
import saints from "./songs/when-the-saints-go-marching-in.cho";
import auldLangSyne from "./songs/auld-lang-syne.cho";

/** Public-domain / traditional songs, written by hand. Loaded as text by the Worker's `.cho` rule. */
export const SEED_SONGS: { file: string; text: string; language: string; categories: string[] }[] = [
  { file: "amazing-grace.cho", text: amazingGrace, language: "en", categories: ["hymn"] },
  { file: "come-thou-fount.cho", text: comeThouFount, language: "en", categories: ["hymn"] },
  { file: "doxology.cho", text: doxology, language: "en", categories: ["hymn"] },
  { file: "it-is-well.cho", text: itIsWell, language: "en", categories: ["hymn"] },
  { file: "when-the-saints-go-marching-in.cho", text: saints, language: "en", categories: ["traditional"] },
  { file: "auld-lang-syne.cho", text: auldLangSyne, language: "en", categories: ["folk", "traditional"] },
];

/** Import the sample songs through the normal import path; re-running only reports duplicates. */
export function seedSongs(db: D1Database, user: AuthUser): Promise<ImportResult> {
  const files: Record<string, Uint8Array> = {};
  for (const s of SEED_SONGS) files[s.file] = strToU8(s.text);
  files["manifest.json"] = strToU8(
    JSON.stringify(SEED_SONGS.map(({ file, language, categories }) => ({ file, language, categories }))),
  );
  return importFiles(db, user, [{ name: "seed.zip", data: zipSync(files) }]);
}
