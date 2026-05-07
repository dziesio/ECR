-- ── sources: two rows, never changes ─────────────────────────────────────────
CREATE TABLE sources (
    id           SMALLINT     PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code         VARCHAR(30)  UNIQUE NOT NULL,
    display_name VARCHAR(100) NOT NULL
);

INSERT INTO sources (code, display_name) VALUES
    ('LIBRUS',          'Librus'),
    ('BRITISH_COUNCIL', 'British Council');

-- ── persons: deduplicated sender/author registry ───────────────────────────────
CREATE TABLE persons (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id SMALLINT     NOT NULL REFERENCES sources(id),
    full_name VARCHAR(255) NOT NULL DEFAULT '',
    role      VARCHAR(255) NOT NULL DEFAULT '',
    CONSTRAINT uq_persons UNIQUE (source_id, full_name, role)
);

CREATE INDEX idx_persons_source ON persons (source_id);

-- ── Migrate senders from messages ─────────────────────────────────────────────
INSERT INTO persons (source_id, full_name, role)
SELECT DISTINCT s.id, m.sender, COALESCE(m.sender_role, '')
FROM messages m
JOIN sources s ON s.code = m.message_source
WHERE m.sender IS NOT NULL AND m.sender != ''
ON CONFLICT ON CONSTRAINT uq_persons DO NOTHING;

ALTER TABLE messages ADD COLUMN sender_id UUID REFERENCES persons(id);

UPDATE messages m
SET sender_id = p.id
FROM persons p
JOIN sources s ON s.id = p.source_id
WHERE s.code          = m.message_source
  AND p.full_name     = m.sender
  AND p.role          = COALESCE(m.sender_role, '');

ALTER TABLE messages DROP COLUMN message_source;
ALTER TABLE messages DROP COLUMN sender;
ALTER TABLE messages DROP COLUMN sender_role;

CREATE INDEX idx_messages_sender ON messages (sender_id);

-- ── Migrate authors from announcements ────────────────────────────────────────
INSERT INTO persons (source_id, full_name, role)
SELECT DISTINCT s.id, a.author, COALESCE(a.author_role, '')
FROM announcements a
JOIN sources s ON s.code = a.source
WHERE a.author IS NOT NULL AND a.author != ''
  AND a.source IS NOT NULL
ON CONFLICT ON CONSTRAINT uq_persons DO NOTHING;

ALTER TABLE announcements ADD COLUMN author_id UUID REFERENCES persons(id);

UPDATE announcements a
SET author_id = p.id
FROM persons p
JOIN sources s ON s.id = p.source_id
WHERE s.code      = a.source
  AND p.full_name = a.author
  AND p.role      = COALESCE(a.author_role, '');

ALTER TABLE announcements DROP COLUMN source;
ALTER TABLE announcements DROP COLUMN author;
ALTER TABLE announcements DROP COLUMN author_role;

CREATE INDEX idx_announcements_author ON announcements (author_id);
