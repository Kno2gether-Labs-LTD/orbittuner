-- Failed portal login attempts, keyed "name:<lowercased name>" or "ip:<address>". Epoch ms.
CREATE TABLE login_attempts (
  key TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX idx_login_attempts_key_at ON login_attempts(key, at);
