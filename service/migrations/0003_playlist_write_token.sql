-- Per-request token set by the playlist row upsert; item writes only apply
-- when the request that wrote the row is the one writing the items.
ALTER TABLE playlists ADD COLUMN write_token TEXT;
