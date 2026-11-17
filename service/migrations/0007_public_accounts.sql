-- Public accounts: roles owner/admin/user (member -> user), plans, Google identity, disabling.
-- SQLite cannot alter a CHECK constraint, so users is rebuilt in place, keeping every id.
-- FK checks are deferred to commit. The parent rows are re-inserted into the new `users`
-- (not renamed in) on purpose: SQLite's deferred-FK counter, raised by the DROP's implicit
-- delete, is only paid back by inserts into the parent table; a RENAME would leave it raised
-- and the commit would fail with FOREIGN KEY constraint failed.
PRAGMA defer_foreign_keys = true;

CREATE TABLE users_backup AS SELECT id, name, role, password_hash, created_at FROM users;

DROP TABLE users;

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  role TEXT CHECK (role IN ('owner','admin','user')),
  password_hash TEXT,
  created_at INTEGER,
  plan TEXT NOT NULL DEFAULT 'free' CHECK (plan IN ('free','plus')),
  plan_expires_at INTEGER,
  google_sub TEXT UNIQUE,
  email TEXT,
  disabled_at INTEGER,
  last_seen_at INTEGER
);

INSERT INTO users (id, name, role, password_hash, created_at)
  SELECT id, name, CASE role WHEN 'member' THEN 'user' ELSE role END, password_hash, created_at FROM users_backup;

DROP TABLE users_backup;

-- Anonymous account creations per client IP key (IPv6 collapsed to /64). Epoch ms.
CREATE TABLE account_attempts (
  ip_key TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX idx_account_attempts_key_at ON account_attempts(ip_key, at);

INSERT OR IGNORE INTO settings (key, value) VALUES ('free_ai_daily', '3');
INSERT OR IGNORE INTO settings (key, value) VALUES ('plus_ai_daily', '50');
INSERT OR IGNORE INTO settings (key, value) VALUES ('google_client_ids', '[]');
INSERT OR IGNORE INTO settings (key, value) VALUES ('flag_hide_threshold', '3');
