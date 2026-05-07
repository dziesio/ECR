-- ECR-Harvester — initial schema
-- PostgreSQL 13+ required (gen_random_uuid() is built-in)

CREATE TABLE IF NOT EXISTS students (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    librus_username VARCHAR(255) NOT NULL UNIQUE,
    full_name       VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS grades (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id   UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    subject_name VARCHAR(255) NOT NULL,
    category     VARCHAR(255),
    grade_value  VARCHAR(10) NOT NULL,
    weight       INT         NOT NULL DEFAULT 1,
    date_issued  DATE,
    teacher      VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_grades_student_subject
    ON grades(student_id, subject_name);

CREATE TABLE IF NOT EXISTS messages (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id        UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    librus_message_id VARCHAR(255) NOT NULL,
    sender            VARCHAR(255),
    subject           VARCHAR(500),
    content           TEXT,
    sent_at           TIMESTAMP,
    CONSTRAINT uq_librus_message_id UNIQUE (librus_message_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_librus_id
    ON messages(librus_message_id);

CREATE TABLE IF NOT EXISTS attendance (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id    UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    date          DATE        NOT NULL,
    lesson_number INT         NOT NULL,
    status        VARCHAR(20) NOT NULL,
    subject       VARCHAR(255),
    CONSTRAINT uq_attendance_slot UNIQUE (student_id, date, lesson_number)
);

CREATE INDEX IF NOT EXISTS idx_attendance_student_date
    ON attendance(student_id, date);
