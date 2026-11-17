-- Find on the web (discover jobs). Append-only; existing jobs become kind 'links'.
ALTER TABLE import_jobs ADD COLUMN kind TEXT NOT NULL DEFAULT 'links' CHECK (kind IN ('links', 'discover'));
-- The normalised search query of a discover job (NULL for link jobs).
ALTER TABLE import_jobs ADD COLUMN query TEXT;

-- Global 24 h cache of web-search candidates per normalised query (+ language), so repeated
-- searches for the same song never pay for another Apify run. Jobs stay per user; only the
-- candidate URL list (a JSON array) is shared.
CREATE TABLE discover_cache (
  query_key TEXT PRIMARY KEY,
  urls TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_discover_cache_created ON discover_cache(created_at);

-- Discovered pages already imported are matched by source URL before any unit is charged.
CREATE INDEX idx_songs_source_url ON songs(source_url);

-- Apify Google Search actor (user~actor; validated on read, default used when malformed).
INSERT OR IGNORE INTO settings (key, value) VALUES ('apify_search_actor', 'apify~google-search-scraper');
