CREATE TABLE announcements (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id              UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    librus_announcement_id  VARCHAR(50) UNIQUE NOT NULL,
    title                   VARCHAR(500),
    content                 TEXT,
    author                  VARCHAR(255),
    published_at            DATE
);

CREATE INDEX idx_announcements_student ON announcements(student_id);
