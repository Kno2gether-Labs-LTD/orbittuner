-- Plan-based AI quotas: one per-user daily AI counter for every AI path (text/URL conversion,
-- link imports, .txt imports, discover), replacing discover_usage (kept, no longer written).
CREATE TABLE user_ai_usage (
  user_id TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (user_id, day)
);
-- Carry today's (and any other) member counts over so the switch does not reset anyone's day.
INSERT INTO user_ai_usage (user_id, day, count) SELECT user_id, day, count FROM discover_usage;

-- AI-capable requests per user (sliding one-minute window). Epoch ms.
CREATE TABLE ai_request_attempts (
  user_id TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX idx_ai_request_attempts_user_at ON ai_request_attempts(user_id, at);
CREATE INDEX idx_ai_request_attempts_at ON ai_request_attempts(at);

-- The hourly cleanup of account_attempts deletes by `at` alone.
CREATE INDEX idx_account_attempts_at ON account_attempts(at);

-- Anonymous accounts (no Google identity) get their own, smaller daily AI allowance.
INSERT OR IGNORE INTO settings (key, value) VALUES ('anon_ai_daily', '1');
