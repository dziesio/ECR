import asyncio
import logging
import os
from contextlib import asynccontextmanager
from urllib.parse import quote
from uuid import UUID

import asyncpg
import httpx
from fastapi import FastAPI
from fastapi.responses import JSONResponse

log = logging.getLogger("ecr-notifier")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

DATABASE_URL      = os.getenv("DATABASE_URL", "postgresql://postgres:postgres@postgres:5432/ecr_harvester")
ECR_API_BASE_URL  = os.getenv("ECR_API_BASE_URL", "http://ecr-api:8081")
BARK_DEVICE_KEYS  = [k.strip() for k in os.getenv("BARK_DEVICE_KEY", "").split(",") if k.strip()]
BARK_ICON_URL     = os.getenv("BARK_ICON_URL", "https://portal.librus.pl/favicon.ico")
BARK_BC_ICON_URL  = os.getenv("BARK_BC_ICON_URL", "")
POLL_INTERVAL_S   = int(os.getenv("NOTIFIER_POLL_INTERVAL_MS", "300000")) / 1000

_SOURCE_DISPLAY = {"BRITISH_COUNCIL": "British Council"}
_SOURCE_ICON    = {"BRITISH_COUNCIL": BARK_BC_ICON_URL}

pool: asyncpg.Pool | None = None


def _display_source(code: str | None) -> str:
    return _SOURCE_DISPLAY.get(code or "", "Librus")


async def _migrate(conn):
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS notifications (
            id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
            entity_id   UUID        NOT NULL,
            entity_type VARCHAR(20) NOT NULL,
            title       VARCHAR(500),
            body        TEXT,
            notified_at TIMESTAMP   NOT NULL DEFAULT now(),
            CONSTRAINT uq_entity UNIQUE (entity_id, entity_type)
        )
    """)


def _icon_for_source(source: str | None) -> str:
    return _SOURCE_ICON.get(source or "", "") or BARK_ICON_URL


async def _send_bark(title: str, body: str, icon: str = ""):
    async with httpx.AsyncClient(timeout=10) as client:
        for key in BARK_DEVICE_KEYS:
            url = f"https://api.day.app/{key}/{quote(title)}/{quote(body)}"
            effective_icon = icon or BARK_ICON_URL
            if effective_icon:
                url += f"?icon={quote(effective_icon, safe=':/')}"
            try:
                r = await client.get(url)
                if r.status_code != 200:
                    log.warning("Bark HTTP %s for key …%s: %s", r.status_code, key[-6:], title)
            except Exception as exc:
                log.warning("Bark failed for key …%s: %s", key[-6:], exc)


async def _poll_once():
    try:
        async with httpx.AsyncClient(base_url=ECR_API_BASE_URL, timeout=30) as http:
            students = (await http.get("/api/students")).json()
            async with pool.acquire() as conn:
                for student in students:
                    sid = student["id"]

                    # ── Messages (INBOX only) ──────────────────────────────
                    for msg in (await http.get(f"/api/students/{sid}/messages")).json():
                        if msg.get("messageType") != "INBOX":
                            continue
                        eid = UUID(msg["id"])
                        if await conn.fetchrow(
                            "SELECT 1 FROM notifications WHERE entity_id=$1 AND entity_type='MESSAGE'", eid
                        ):
                            continue
                        src    = _display_source(msg.get("messageSource"))
                        sender = msg.get("sender", "")
                        role   = msg.get("senderRole") or ""
                        title  = f"{src}: New message – {sender}" + (f" [{role}]" if role else "")
                        body   = msg.get("subject", "")
                        await _send_bark(title, body, _icon_for_source(msg.get("messageSource")))
                        await conn.execute(
                            "INSERT INTO notifications(entity_id,entity_type,title,body)"
                            " VALUES($1,'MESSAGE',$2,$3) ON CONFLICT DO NOTHING",
                            eid, title, body,
                        )
                        log.info("Notified MESSAGE %s", eid)

                    # ── Announcements ──────────────────────────────────────
                    for ann in (await http.get(f"/api/students/{sid}/announcements")).json():
                        eid = UUID(ann["id"])
                        if await conn.fetchrow(
                            "SELECT 1 FROM notifications WHERE entity_id=$1 AND entity_type='ANNOUNCEMENT'", eid
                        ):
                            continue
                        src    = _display_source(ann.get("source"))
                        author = ann.get("author", "")
                        role   = ann.get("authorRole") or ""
                        title  = f"{src}: Announcement – {author}" + (f" [{role}]" if role else "")
                        body   = ann.get("title", "")
                        await _send_bark(title, body, _icon_for_source(ann.get("source")))
                        await conn.execute(
                            "INSERT INTO notifications(entity_id,entity_type,title,body)"
                            " VALUES($1,'ANNOUNCEMENT',$2,$3) ON CONFLICT DO NOTHING",
                            eid, title, body,
                        )
                        log.info("Notified ANNOUNCEMENT %s", eid)
    except Exception as exc:
        log.error("Poll cycle failed: %s", exc, exc_info=True)


async def _polling_loop():
    while True:
        await _poll_once()
        await asyncio.sleep(POLL_INTERVAL_S)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(DATABASE_URL, min_size=1, max_size=3)
    async with pool.acquire() as conn:
        await _migrate(conn)
    task = asyncio.create_task(_polling_loop())
    yield
    task.cancel()
    await pool.close()


app = FastAPI(title="ECR Notifier", lifespan=lifespan)


@app.get("/actuator/health")
async def health():
    return {"status": "UP"}


@app.post("/api/notifications/resend-recent")
async def resend_recent():
    async with pool.acquire() as conn:
        rows = await conn.fetch("""
            (SELECT entity_id, title, body FROM notifications
             WHERE entity_type = 'MESSAGE' ORDER BY notified_at DESC LIMIT 3)
            UNION ALL
            (SELECT entity_id, title, body FROM notifications
             WHERE entity_type = 'ANNOUNCEMENT' ORDER BY notified_at DESC LIMIT 3)
        """)
    sent = []
    for row in rows:
        t = row["title"] or "Notification"
        b = row["body"] or ""
        await _send_bark(t, b)
        sent.append({"entityId": str(row["entity_id"]), "title": t})
    return JSONResponse({"resent": sent})
