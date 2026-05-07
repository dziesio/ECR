# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies first — layer is cached unless pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src ./src
RUN mvn package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Install Google Chrome stable
# --disable-dev-shm-usage in SeleniumConfig tells Chrome to use /tmp instead
# of /dev/shm, so the small default shm size in Docker is not an issue.
RUN apt-get update -q && \
    apt-get install -y -q --no-install-recommends wget gnupg ca-certificates && \
    wget -qO- https://dl-ssl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg && \
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] \
        http://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list && \
    apt-get update -q && \
    apt-get install -y -q --no-install-recommends google-chrome-stable && \
    rm -rf /var/lib/apt/lists/*

# Non-root user — Chrome must not run as root inside a container
RUN groupadd -r harvester && useradd -r -g harvester -m harvester

# WebDriverManager caches the matching ChromeDriver binary here
RUN mkdir -p /home/harvester/.cache/selenium && \
    chown -R harvester:harvester /home/harvester/.cache

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown harvester:harvester app.jar

USER harvester

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
