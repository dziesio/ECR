import os
from contextlib import asynccontextmanager
from typing import Optional
from datetime import date, datetime
from uuid import UUID

import asyncpg
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel


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


# ── Models ────────────────────────────────────────────────────────────────────

class StudentResponse(BaseModel):
    id: UUID
    librus_username: Optional[str] = None
    bc_id: Optional[str] = None
    full_name: str
    class_name: Optional[str] = None
    created_at: datetime

class GradeResponse(BaseModel):
    id: UUID
    subject_name: str
    category: Optional[str] = None
    grade_value: str
    weight: int
    date_issued: Optional[date] = None
    teacher: Optional[str] = None

class MessageResponse(BaseModel):
    id: UUID
    message_type: Optional[str] = None
    message_source: Optional[str] = None
    sender: Optional[str] = None
    sender_role: Optional[str] = None
    subject: Optional[str] = None
    content: Optional[str] = None
    sent_at: Optional[datetime] = None

class AttendanceResponse(BaseModel):
    id: UUID
    date: date
    lesson_number: int
    status: str
    subject: Optional[str] = None

class AnnouncementResponse(BaseModel):
    id: UUID
    source: Optional[str] = None
    title: str
    content: Optional[str] = None
    author: Optional[str] = None
    author_role: Optional[str] = None
    published_at: Optional[date] = None


# ── Helpers ───────────────────────────────────────────────────────────────────

async def require_student(student_id: UUID):
    row = await pool.fetchrow("SELECT id FROM students WHERE id = $1", student_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Student not found")


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/actuator/health")
async def health():
    return {"status": "UP"}


@app.get("/api/students", response_model=list[StudentResponse])
async def list_students():
    rows = await pool.fetch("SELECT * FROM students ORDER BY created_at")
    return [dict(r) for r in rows]


@app.get("/api/students/{student_id}", response_model=StudentResponse)
async def get_student(student_id: UUID):
    row = await pool.fetchrow("SELECT * FROM students WHERE id = $1", student_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Student not found")
    return dict(row)


@app.get("/api/students/{student_id}/grades", response_model=list[GradeResponse])
async def get_grades(student_id: UUID, subject: Optional[str] = Query(None)):
    await require_student(student_id)
    if subject:
        rows = await pool.fetch(
            "SELECT * FROM grades WHERE student_id = $1 AND subject_name = $2 ORDER BY date_issued DESC",
            student_id, subject,
        )
    else:
        rows = await pool.fetch(
            "SELECT * FROM grades WHERE student_id = $1 ORDER BY date_issued DESC",
            student_id,
        )
    return [dict(r) for r in rows]


@app.get("/api/students/{student_id}/messages", response_model=list[MessageResponse])
async def get_messages(student_id: UUID):
    await require_student(student_id)
    rows = await pool.fetch(
        """
        SELECT m.id, m.message_type, s.code AS message_source,
               p.full_name AS sender, NULLIF(p.role, '') AS sender_role,
               m.subject, m.content, m.sent_at
        FROM messages m
        LEFT JOIN persons  p ON m.sender_id  = p.id
        LEFT JOIN sources  s ON p.source_id  = s.id
        WHERE m.student_id = $1
        ORDER BY m.sent_at DESC
        """,
        student_id,
    )
    return [dict(r) for r in rows]


@app.get("/api/students/{student_id}/announcements", response_model=list[AnnouncementResponse])
async def get_announcements(student_id: UUID):
    await require_student(student_id)
    rows = await pool.fetch(
        """
        SELECT a.id, s.code AS source, a.title, a.content,
               p.full_name AS author, NULLIF(p.role, '') AS author_role,
               a.published_at
        FROM announcements a
        LEFT JOIN persons  p ON a.author_id  = p.id
        LEFT JOIN sources  s ON p.source_id  = s.id
        WHERE a.student_id = $1
        ORDER BY a.published_at DESC
        """,
        student_id,
    )
    return [dict(r) for r in rows]


@app.get("/api/students/{student_id}/attendance", response_model=list[AttendanceResponse])
async def get_attendance(
    student_id: UUID,
    status: Optional[str] = Query(None, description="PRESENT, ABSENT, LATE, EXCUSED"),
):
    await require_student(student_id)
    if status:
        rows = await pool.fetch(
            "SELECT * FROM attendance WHERE student_id = $1 AND status = $2 ORDER BY date DESC, lesson_number ASC",
            student_id, status.upper(),
        )
    else:
        rows = await pool.fetch(
            "SELECT * FROM attendance WHERE student_id = $1 ORDER BY date DESC, lesson_number ASC",
            student_id,
        )
    return [dict(r) for r in rows]
