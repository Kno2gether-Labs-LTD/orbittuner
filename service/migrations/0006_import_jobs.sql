-- Bulk link-import jobs (one queue message per item) and per-user daily link-import usage.
CREATE TABLE import_jobs (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  created_at INTEGER,
  total INTEGER,
  done INTEGER DEFAULT 0,
  status TEXT CHECK (status IN ('queued','running','done')),
  -- Refreshed whenever an item starts or finishes; the stale sweep keys on it.
  last_progress_at INTEGER
);
CREATE INDEX idx_import_jobs_user ON import_jobs(user_id);

CREATE TABLE import_job_items (
  job_id TEXT,
  idx INTEGER,
  url TEXT,
  status TEXT CHECK (status IN ('queued','done','duplicate','review','failed','limit_reached')),
  song_id TEXT,
  error TEXT,
  -- Set when a consumer starts the item; an AI-limit stop only closes items not yet started.
  started_at INTEGER,
  PRIMARY KEY (job_id, idx)
);

-- Counts member link imports per UTC day against settings.discover_daily_limit.
CREATE TABLE discover_usage (
  user_id TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (user_id, day)
);
