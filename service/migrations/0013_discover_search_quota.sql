-- Discover review fixes: daily web-search quotas and per-entry cache expiry.
-- Uncached web searches (Apify runs) per UTC day. scope: 'user' (key = user id), 'ip' (key =
-- client IP key, IPv6 collapsed to /64) or 'global' (key = '*'). Cache hits are never counted.
CREATE TABLE search_usage (
  scope TEXT NOT NULL CHECK (scope IN ('user', 'ip', 'global')),
  key TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (scope, key, day)
);

-- Found candidates are cached 24 h; a search whose results were all filtered out only 1 h.
-- Rows written before this migration fall back to created_at + 24 h.
ALTER TABLE discover_cache ADD COLUMN expires_at INTEGER;

-- Per-plan searches per day (owner/admin unlimited), per client IP, and a global circuit breaker.
INSERT OR IGNORE INTO settings (key, value) VALUES ('search_daily_anon', '2');
INSERT OR IGNORE INTO settings (key, value) VALUES ('search_daily_free', '10');
INSERT OR IGNORE INTO settings (key, value) VALUES ('search_daily_plus', '50');
INSERT OR IGNORE INTO settings (key, value) VALUES ('search_daily_per_ip', '30');
INSERT OR IGNORE INTO settings (key, value) VALUES ('search_daily_global', '500');
