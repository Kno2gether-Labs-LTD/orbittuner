-- OrbitTuner schema. All times are epoch milliseconds; ids are ULIDs.
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  role TEXT CHECK (role IN ('owner','member')),
  password_hash TEXT,
  created_at INTEGER
);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES users(id),
  name TEXT,
  token_hash TEXT UNIQUE,
  revoked_at INTEGER,
  last_seen_at INTEGER,
  created_at INTEGER
);

CREATE TABLE pairing_codes (
  code_hash TEXT PRIMARY KEY,
  user_id TEXT,
  expires_at INTEGER,
  used_at INTEGER
);

CREATE TABLE sessions (
  id_hash TEXT PRIMARY KEY,
  user_id TEXT,
  csrf TEXT,
  expires_at INTEGER
);

CREATE TABLE songs (
  id TEXT PRIMARY KEY,
  title TEXT,
  artist TEXT,
  chordpro TEXT NOT NULL,
  chordpro_native TEXT,
  original_key TEXT,
  tempo_bpm INTEGER,
  time_sig TEXT,
  capo INTEGER DEFAULT 0,
  language TEXT DEFAULT 'en',
  categories TEXT DEFAULT '[]',
  difficulty TEXT,
  source TEXT CHECK (source IN ('owner','web','import')),
  source_url TEXT,
  status TEXT CHECK (status IN ('published','review','hidden')),
  created_by TEXT,
  version INTEGER DEFAULT 1,
  created_at INTEGER,
  updated_at INTEGER,
  dedupe_key TEXT
);
CREATE INDEX idx_songs_dedupe_key ON songs(dedupe_key);
CREATE INDEX idx_songs_updated_at ON songs(updated_at);

CREATE TABLE song_versions (
  id TEXT PRIMARY KEY,
  song_id TEXT,
  chordpro TEXT,
  source_url TEXT,
  created_at INTEGER
);

CREATE VIRTUAL TABLE songs_fts USING fts5(
  song_id UNINDEXED,
  title,
  artist,
  lyrics,
  categories,
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE favourites (
  user_id TEXT,
  song_id TEXT,
  favourite INTEGER,
  updated_at INTEGER,
  PRIMARY KEY (user_id, song_id)
);

CREATE TABLE playlists (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  name TEXT,
  deleted INTEGER DEFAULT 0,
  updated_at INTEGER
);

CREATE TABLE playlist_items (
  playlist_id TEXT,
  song_id TEXT,
  position TEXT,
  settings TEXT DEFAULT '{}',
  PRIMARY KEY (playlist_id, song_id)
);

CREATE TABLE reports (
  id TEXT PRIMARY KEY,
  song_id TEXT,
  user_id TEXT,
  note TEXT,
  created_at INTEGER,
  resolved_at INTEGER
);

CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT
);
INSERT INTO settings (key, value) VALUES ('discover_daily_limit', '20');
