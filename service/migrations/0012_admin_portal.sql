-- Admin portal (S6).
-- Anonymous AI actions per client IP key (IPv6 collapsed to /64) per UTC day, so many anonymous
-- accounts created from one network cannot drain the global AI budget. Counted alongside
-- user_ai_usage for anonymous accounts only; limit in settings.anon_ai_daily_per_ip.
CREATE TABLE ip_ai_usage (
  ip_key TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (ip_key, day)
);
INSERT OR IGNORE INTO settings (key, value) VALUES ('anon_ai_daily_per_ip', '10');

-- Users page: songs added per user, and "seen today" counts.
CREATE INDEX IF NOT EXISTS idx_songs_created_by ON songs(created_by);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen_at);

-- Review queues: open reports per song, status tabs, "added today".
CREATE INDEX IF NOT EXISTS idx_reports_song_open ON reports(song_id, resolved_at);
CREATE INDEX IF NOT EXISTS idx_songs_status ON songs(status);
CREATE INDEX IF NOT EXISTS idx_songs_created_at ON songs(created_at);

-- The client IP key an import job's AI units were also reserved under (anonymous accounts only),
-- so the queue consumer refunds the per-IP counter too. NULL when no IP unit was reserved.
ALTER TABLE import_jobs ADD COLUMN ip_key TEXT;

-- One-time links that let a promoted admin set a portal password (owner-issued). Only the
-- SHA-256 of the token is stored; 24 h expiry; used_at makes a link single-use.
CREATE TABLE admin_setup_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  -- Random per-redemption value set by the claim; the password update requires it.
  claim_nonce TEXT
);
CREATE INDEX idx_admin_setup_tokens_user ON admin_setup_tokens(user_id);
