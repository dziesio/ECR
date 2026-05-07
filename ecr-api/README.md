# ecr-api

Read-only REST API that exposes data collected by ecr-harvester. The schema is owned by ecr-harvester (Flyway); ecr-api connects to the same PostgreSQL database in read-only mode (`ddl-auto=none`).

## Endpoints

Base path: `/api/students`

| Method | Path | Description |
|---|---|---|
| GET | `/api/students` | List all students |
| GET | `/api/students/{id}` | Get a student by UUID |
| GET | `/api/students/{id}/grades` | Get all grades; optional `?subject=` filter |
| GET | `/api/students/{id}/messages` | Get all messages ordered by date descending |
| GET | `/api/students/{id}/attendance` | Get attendance records; optional `?status=` filter |

### Message response fields

Each message includes a `messageType` field — either `INBOX` (received) or `SENT`.

### Attendance status values

`PRESENT`, `ABSENT`, `LATE`, `EXCUSED`

## Interactive docs

Swagger UI is available at `http://localhost:8081/docs` when running locally.  
OpenAPI JSON is at `http://localhost:8081/api-docs`.

## Configuration

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/ecr_harvester` | Shared database |
| `spring.datasource.username` | `postgres` | |
| `spring.datasource.password` | `postgres` | |

## Running locally

```bash
# Start PostgreSQL and ecr-harvester first so the schema exists
docker compose up -d postgres ecr-harvester

mvn spring-boot:run
```

## Running tests

```bash
mvn test
```

Tests use MockMvc and Mockito — no database or browser required.
