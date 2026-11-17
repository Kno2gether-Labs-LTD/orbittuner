CREATE TABLE secrets (
  name TEXT PRIMARY KEY,
  ciphertext TEXT NOT NULL,
  iv TEXT NOT NULL,
  last4 TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE ai_usage (
  day TEXT PRIMARY KEY,
  count INTEGER NOT NULL
);

INSERT OR IGNORE INTO settings (key, value) VALUES ('ai_daily_limit', '100');
INSERT OR IGNORE INTO settings (key, value) VALUES ('ai_max_output_tokens', '4000');
-- Placeholder: Task 2's getProviders() falls back to built-in defaults when this is empty.
INSERT OR IGNORE INTO settings (key, value) VALUES ('ai_providers', '[]');
INSERT OR IGNORE INTO settings (key, value) VALUES ('apify_actor', 'apify~rag-web-browser');
