import os
from contextlib import asynccontextmanager
from typing import Optional
from uuid import UUID

import asyncpg
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse


pool: asyncpg.Pool | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(
        os.getenv("DATABASE_URL", "postgresql://postgres:postgres@postgres:5432/ecr_harvester"),
        min_size=1,
        max_size=3,
    )
    yield
    await pool.close()


app = FastAPI(title="ECR API", docs_url="/docs", lifespan=lifespan)


def rows_to_json(rows) -> list[dict]:
    result = []
    for row in rows:
        d = {}
        for k, v in row.items():
            if hasattr(v, "isoformat"):
                d[k] = v.isoformat()
            elif hasattr(v, "hex"):
                d[k] = str(v)
            else:
                d[k] = v
        result.append(d)
    return result


def row_to_json(row) -> dict:
    return rows_to_json([row])[0]


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/actuator/health")
async def health():
    return {"status": "UP"}


@app.get("/api/students")
async def list_students():
    rows = await pool.fetch("""
        SELECT id,
               librus_username AS "librusUsername",
               bc_id           AS "bcId",
               full_name       AS "fullName",
               class_name      AS "className",
               created_at      AS "createdAt"
        FROM students
        ORDER BY created_at
    """)
    return JSONResponse(rows_to_json(rows))


@app.get("/api/students/{student_id}")
async def get_student(student_id: UUID):
    row = await pool.fetchrow("""
        SELECT id,
               librus_username AS "librusUsername",
               bc_id           AS "bcId",
               full_name       AS "fullName",
               class_name      AS "className",
               created_at      AS "createdAt"
        FROM students
        WHERE id = $1
    """, student_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Student not found")
    return JSONResponse(row_to_json(row))


@app.get("/api/students/{student_id}/grades")
async def get_grades(student_id: UUID, subject: Optional[str] = Query(None)):
    await _require_student(student_id)
    if subject:
        rows = await pool.fetch("""
            SELECT id,
                   subject_name AS "subjectName",
                   category,
                   grade_value  AS "gradeValue",
                   weight,
                   date_issued  AS "dateIssued",
                   teacher
            FROM grades
            WHERE student_id = $1 AND subject_name = $2
            ORDER BY date_issued DESC
        """, student_id, subject)
    else:
        rows = await pool.fetch("""
            SELECT id,
                   subject_name AS "subjectName",
                   category,
                   grade_value  AS "gradeValue",
                   weight,
                   date_issued  AS "dateIssued",
                   teacher
            FROM grades
            WHERE student_id = $1
            ORDER BY date_issued DESC
        """, student_id)
    return JSONResponse(rows_to_json(rows))


@app.get("/api/students/{student_id}/messages")
async def get_messages(student_id: UUID):
    await _require_student(student_id)
    rows = await pool.fetch("""
        SELECT m.id,
               m.message_type       AS "messageType",
               s.code               AS "messageSource",
               p.full_name          AS "sender",
               NULLIF(p.role, '')   AS "senderRole",
               m.subject,
               m.content,
               m.sent_at            AS "sentAt"
        FROM messages m
        LEFT JOIN persons p ON m.sender_id = p.id
        LEFT JOIN sources s ON p.source_id = s.id
        WHERE m.student_id = $1
        ORDER BY m.sent_at DESC
    """, student_id)
    return JSONResponse(rows_to_json(rows))


@app.get("/api/students/{student_id}/announcements")
async def get_announcements(student_id: UUID):
    await _require_student(student_id)
    rows = await pool.fetch("""
        SELECT a.id,
               s.code               AS "source",
               a.title,
               a.content,
               p.full_name          AS "author",
               NULLIF(p.role, '')   AS "authorRole",
               a.published_at       AS "publishedAt"
        FROM announcements a
        LEFT JOIN persons p ON a.author_id = p.id
        LEFT JOIN sources s ON p.source_id = s.id
        WHERE a.student_id = $1
        ORDER BY a.published_at DESC
    """, student_id)
    return JSONResponse(rows_to_json(rows))


@app.get("/api/students/{student_id}/attendance")
async def get_attendance(
    student_id: UUID,
    status: Optional[str] = Query(None, description="PRESENT, ABSENT, LATE, EXCUSED"),
):
    await _require_student(student_id)
    if status:
        rows = await pool.fetch("""
            SELECT id,
                   date,
                   lesson_number AS "lessonNumber",
                   status,
                   subject
            FROM attendance
            WHERE student_id = $1 AND status = $2
            ORDER BY date DESC, lesson_number ASC
        """, student_id, status.upper())
    else:
        rows = await pool.fetch("""
            SELECT id,
                   date,
                   lesson_number AS "lessonNumber",
                   status,
                   subject
            FROM attendance
            WHERE student_id = $1
            ORDER BY date DESC, lesson_number ASC
        """, student_id)
    return JSONResponse(rows_to_json(rows))


async def _require_student(student_id: UUID):
    row = await pool.fetchrow("SELECT id FROM students WHERE id = $1", student_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Student not found")
