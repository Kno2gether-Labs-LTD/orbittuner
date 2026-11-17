/** Languages offered in the editor. Codes are what devices filter on; names are for people. */
export const LANGUAGES: [code: string, name: string][] = [
  ["en", "English"],
  ["bn", "Bengali"],
  ["hi", "Hindi"],
  ["ur", "Urdu"],
  ["pa", "Punjabi"],
  ["ta", "Tamil"],
  ["te", "Telugu"],
  ["mr", "Marathi"],
  ["gu", "Gujarati"],
  ["kn", "Kannada"],
  ["ml", "Malayalam"],
  ["ne", "Nepali"],
  ["es", "Spanish"],
  ["pt", "Portuguese"],
  ["fr", "French"],
  ["de", "German"],
  ["it", "Italian"],
  ["nl", "Dutch"],
  ["sv", "Swedish"],
  ["pl", "Polish"],
  ["ru", "Russian"],
  ["uk", "Ukrainian"],
  ["ar", "Arabic"],
  ["tr", "Turkish"],
  ["ja", "Japanese"],
  ["ko", "Korean"],
  ["zh", "Chinese"],
  ["la", "Latin"],
];

const NAMES = new Map(LANGUAGES);

export function languageName(code: string): string {
  return NAMES.get(code) ?? code.toUpperCase();
}
