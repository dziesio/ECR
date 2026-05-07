# ECR-Harvester

A scheduled Selenium-based scraper for the [Librus Synergia](https://synergia.librus.pl/) student portal and the [British Council Learning Hub](https://learninghub.britishcouncil.org/).

The application automatically logs into each portal, collects student data such as grades, messages, and attendance, and stores everything in a PostgreSQL database. It is designed to run continuously in the background with randomized scraping intervals to reduce the likelihood of bot detection.

---

## Features

* Automated login to Librus Synergia
* Automated login to British Council Learning Hub
* Scrapes:

  * Grades
  * Attendance
  * Inbox messages (Librus + British Council)
  * Sent messages (Librus)
* Tags each message with its source (`LIBRUS` or `BRITISH_COUNCIL`)
* Stores all data in PostgreSQL
* Uses Flyway for automatic database migrations
* Headless Chrome support via Selenium
* Configurable scraping interval with random jitter
* Skips already imported messages to reduce unnecessary requests
* Docker-friendly configuration using environment variables

---

## Tech Stack

* Java 21
* Spring Boot
* Selenium WebDriver
* PostgreSQL
* Flyway
* Maven
* Docker / Docker Compose

---

## How It Works

1. The application starts and immediately triggers an initial scraping session.
2. A headless Chrome browser is launched using Selenium.
3. The scraper logs into the Librus portal and collects:

   * Student name and class (from the grades page)
   * Grades
   * Attendance
   * Inbox and sent messages
4. A separate browser session logs into the British Council Learning Hub and collects:

   * News feed posts from the first listed course
5. New records are saved into PostgreSQL. Messages are tagged with their source.
6. Already imported messages are skipped to minimize session duration.
7. The next scraping cycle is scheduled using:

```text
interval ± random_jitter
```

Example:

```text
10 minutes ± 3 minutes
```

---

## Configuration

All configuration values can be overridden using environment variables.

### Librus

| Property                   | Environment Variable       | Default                                             | Description                  |
| -------------------------- | -------------------------- | --------------------------------------------------- | ---------------------------- |
| `librus.username`          | `LIBRUS_USERNAME`          | —                                                   | Librus login (required)      |
| `librus.password`          | `LIBRUS_PASSWORD`          | —                                                   | Librus password (required)   |
| `librus.full-name`         | `LIBRUS_FULL_NAME`         | `Student`                                           | Fallback student name        |
| `librus.url.login`         | `LIBRUS_URL_LOGIN`         | `https://portal.librus.pl/rodzina`                  | Login page                   |
| `librus.url.grades`        | `LIBRUS_URL_GRADES`        | `https://synergia.librus.pl/przegladaj_oceny/uczen` | Grades page                  |
| `librus.url.messages`      | `LIBRUS_URL_MESSAGES`      | `https://synergia.librus.pl/wiadomosci`             | Messages base URL (`/5` inbox, `/6` sent) |
| `librus.url.attendance`    | `LIBRUS_URL_ATTENDANCE`    | `https://synergia.librus.pl/przegladaj_nb/uczen`    | Attendance page              |

### British Council Learning Hub

| Property       | Environment Variable | Default | Description                              |
| -------------- | -------------------- | ------- | ---------------------------------------- |
| `bc.enabled`   | `BC_ENABLED`         | `false` | Enable British Council scraping          |
| `bc.username`  | `BC_USERNAME`        | —       | Learning Hub login (required if enabled) |
| `bc.password`  | `BC_PASSWORD`        | —       | Learning Hub password (required if enabled) |

### Scraper & Selenium

| Property                   | Environment Variable       | Default | Description                  |
| -------------------------- | -------------------------- | ------- | ---------------------------- |
| `scraper.interval-minutes` | `SCRAPER_INTERVAL_MINUTES` | `10`    | Base scraping interval       |
| `scraper.jitter-minutes`   | `SCRAPER_JITTER_MINUTES`   | `3`     | Randomized interval offset   |
| `selenium.headless`        | `SELENIUM_HEADLESS`        | `true`  | Run browser in headless mode |

---

## Database

Database schema is managed automatically with Flyway migrations.

### Migrations

| Migration                      | Description                              |
| ------------------------------ | ---------------------------------------- |
| `V1__init.sql`                 | Creates initial tables                   |
| `V2__add_message_type.sql`     | Adds inbox/sent message type             |
| `V3__add_student_class.sql`    | Adds student class information           |
| `V4__add_message_source.sql`   | Adds message source (Librus / British Council) |

### Main Tables

* `students`
* `grades`
* `messages`
* `attendance`

The student record is keyed by the Librus username, allowing class information to change over time without creating duplicate students.

---

## Running Locally

### Requirements

* Java 21+
* Maven
* PostgreSQL
* Google Chrome / Chromium

---

### 1. Start PostgreSQL

Using Docker Compose:

```bash
docker compose up -d postgres
```

---

### 2. Set Environment Variables

```bash
export LIBRUS_USERNAME=your_username
export LIBRUS_PASSWORD=your_password
```

To enable British Council scraping:

```bash
export BC_ENABLED=true
export BC_USERNAME=your_bc_username
export BC_PASSWORD=your_bc_password
```

Optional:

```bash
export SCRAPER_INTERVAL_MINUTES=15
export SCRAPER_JITTER_MINUTES=5
```

---

### 3. Run the Application

```bash
mvn spring-boot:run
```

---

## Running Tests

```bash
mvn test
```

Tests use Mockito and do not require:

* Selenium
* Chrome
* PostgreSQL

---

## Docker Example

Example `docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ecr
      POSTGRES_USER: ecr
      POSTGRES_PASSWORD: ecr
    ports:
      - "5432:5432"

  scraper:
    build: .
    depends_on:
      - postgres
    environment:
      LIBRUS_USERNAME: your_username
      LIBRUS_PASSWORD: your_password
      BC_ENABLED: "true"
      BC_USERNAME: your_bc_username
      BC_PASSWORD: your_bc_password
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ecr
      SPRING_DATASOURCE_USERNAME: ecr
      SPRING_DATASOURCE_PASSWORD: ecr
```

Run:

```bash
docker compose up --build
```

---

## Scheduling Strategy

To avoid predictable scraping behavior, the application adds randomized jitter to each execution interval.

Example:

```text
Base interval: 10 minutes
Jitter: ±3 minutes
```

Possible execution times:

* 7 minutes
* 10 minutes
* 13 minutes

This helps reduce the chance of automated activity detection.

---

## Project Structure

```text
src/
 ├── main/
 │   ├── java/
 │   └── resources/
 │       └── db/migration/
 └── test/
```

---

## Security Notes

* Never commit credentials to the repository
* Prefer environment variables or Docker secrets
* Use a dedicated PostgreSQL user with limited permissions

---

## Disclaimer

This project is intended for educational and personal use only.

Users are responsible for ensuring compliance with the Librus and British Council Terms of Service and any applicable regulations.

---

## License

MIT License (or your preferred license)

---

## Contributing

Contributions are welcome.

Possible improvements:

* Multi-account support
* Notifications/webhooks
* REST API
* Metrics and monitoring
* Screenshot/debug mode
* Better anti-detection handling

Feel free to open issues or submit pull requests.
