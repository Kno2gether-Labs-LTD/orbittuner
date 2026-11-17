-- Sing a line (beta): POST /v1/voice keeps its own daily allowance in search_usage under the
-- scopes 'voice_user' (key = user id), 'voice_ip' (client IP key) and 'voice_global' (key = '*').
-- The scope CHECK from 0013 only allows the search scopes, so the table is rebuilt (SQLite cannot
-- alter a CHECK); existing search counters are copied unchanged.
CREATE TABLE search_usage_new (
  scope TEXT NOT NULL CHECK (scope IN ('user', 'ip', 'global', 'voice_user', 'voice_ip', 'voice_global')),
  key TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (scope, key, day)
);
INSERT INTO search_usage_new (scope, key, day, count) SELECT scope, key, day, count FROM search_usage;
DROP TABLE search_usage;
ALTER TABLE search_usage_new RENAME TO search_usage;

-- Voice searches per plan per UTC day (owner/admin unlimited), per client IP, and a global breaker.
INSERT OR IGNORE INTO settings (key, value) VALUES ('voice_daily_anon', '3');
INSERT OR IGNORE INTO settings (key, value) VALUES ('voice_daily_free', '10');
INSERT OR IGNORE INTO settings (key, value) VALUES ('voice_daily_plus', '50');
INSERT OR IGNORE INTO settings (key, value) VALUES ('voice_daily_per_ip', '40');
INSERT OR IGNORE INTO settings (key, value) VALUES ('voice_daily_global', '1000');
