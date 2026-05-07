ALTER TABLE announcements ADD COLUMN IF NOT EXISTS source      VARCHAR(50);
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS author_role VARCHAR(255);

UPDATE announcements SET source = 'LIBRUS' WHERE source IS NULL;
