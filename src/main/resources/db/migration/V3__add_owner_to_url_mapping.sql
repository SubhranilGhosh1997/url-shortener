-- Every short link belongs to a user.
--
-- NOT NULL with no default: this fails if url_mapping already has rows. When this was written the
-- table held only test data, which was deleted by hand first. A real database with existing links
-- would need a backfill step here instead.
--
-- Deliberately NO foreign key to users(id): links and users may later live in different services
-- and databases. Ownership is still trustworthy because user_id always comes from a signed JWT.
ALTER TABLE url_mapping ADD COLUMN user_id BIGINT NOT NULL;

-- "My links", newest first: WHERE user_id = ? ORDER BY id DESC.
CREATE INDEX idx_url_mapping_user_id ON url_mapping (user_id, id);

-- Reuse is now per user: the same URL shortened by two users gives two separate links.
DROP INDEX idx_url_mapping_original_url;
CREATE INDEX idx_url_mapping_user_url ON url_mapping (user_id, original_url);
