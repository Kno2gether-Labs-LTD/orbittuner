-- Server-stamped change marker for sync cursors (client updated_at can skew).
ALTER TABLE playlists ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0;
UPDATE playlists SET modified_at = COALESCE(updated_at, 0);
CREATE INDEX idx_playlists_user_modified ON playlists(user_id, modified_at);
