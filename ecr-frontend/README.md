# ecr-frontend

Login-protected Thymeleaf web UI for browsing data collected by ecr-harvester. Fetches student data from ecr-api over HTTP and renders it in a tabbed interface. Has its own user management (separate from Librus credentials).

## Features

- Secure login with BCrypt-hashed passwords
- Per-student view with tabbed grades, messages, and attendance
- Messages distinguished as Inbox (`↓`) or Sent (`↑`) with counts in the tab badge
- Student class name displayed and refreshed automatically on each scrape
- Admin panel for user management
- Forced password change flow (configurable per user)

## Default credentials

On first startup a default admin account is created:

| Username | Password |
|---|---|
| `admin` | `admin` |

**Change this password immediately after first login.**

## Adding users

Only admin users can create new accounts. Log in as admin and go to `/admin/users`. New users are created with `force_password_change = true`, so they must set a new password on first login.

## Pages

| Path | Description |
|---|---|
| `/` | Student selection — lists all scraped students |
| `/student/{id}` | Tabbed view: Grades / Messages / Attendance |
| `/profile` | Change own password |
| `/admin/users` | User management (admin only) |
| `/login` | Login form |

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `server.port` | — | `8082` | HTTP port |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/ecr_harvester` | Shared database |
| `ecr.api.base-url` | `ECR_API_BASE_URL` | `http://localhost:8081` | ecr-api base URL |

Flyway uses a separate history table (`flyway_schema_history_frontend`) so its migrations don't conflict with ecr-harvester's in the shared database.

## Database

| Migration | Description |
|---|---|
| `V1__init_users.sql` | Creates the `app_users` table |

## Running locally

```bash
# ecr-api must be reachable at ecr.api.base-url
docker compose up -d postgres ecr-harvester ecr-api

mvn spring-boot:run
# open http://localhost:8082
```
