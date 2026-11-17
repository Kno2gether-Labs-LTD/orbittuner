-- Moderation hardening (S4 review).
-- Client IP key (IPv6 collapsed to /64) of each flag, for the per-IP flag rate limit.
ALTER TABLE song_flags ADD COLUMN ip_key TEXT;
CREATE INDEX idx_song_flags_user_at ON song_flags(user_id, created_at);
CREATE INDEX idx_song_flags_ip_at ON song_flags(ip_key, created_at);

-- Songs created by the owner/admins count as moderated (pinned): flags never auto-hide them.
UPDATE songs
   SET moderated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000,
       moderated_by = created_by
 WHERE moderated_at IS NULL
   AND created_by IN (SELECT id FROM users WHERE role IN ('owner', 'admin'));
