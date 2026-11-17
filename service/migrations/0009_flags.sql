-- Song flags: one per (song, user), so a user can never flag the same song twice. When the
-- distinct flag count reaches settings.flag_hide_threshold, a published song that no admin has
-- moderated becomes hidden (in the same batch as the flag insert). The old `reports` table is
-- still written (first flag per user only) so the portal Review page keeps listing notes.
CREATE TABLE song_flags (
  song_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  reason TEXT NOT NULL CHECK (reason IN ('wrong_chords','wrong_lyrics','not_a_song','other')),
  note TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (song_id, user_id)
);
-- The merge path moves flags by user.
CREATE INDEX idx_song_flags_user ON song_flags(user_id);

-- An admin publish/hide decision pins the song: flags no longer auto-hide it.
ALTER TABLE songs ADD COLUMN moderated_at INTEGER;
ALTER TABLE songs ADD COLUMN moderated_by TEXT;
