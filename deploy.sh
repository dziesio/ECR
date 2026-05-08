#!/bin/bash
# Deploys ECR services directly on the VPS (no Docker containers).
# Run as root on the VPS. Safe to re-run — skips steps already done.
set -e
cd ~/ecr

source .env

# ── Java 21 ───────────────────────────────────────────────────────────────────

if ! java -version &>/dev/null 2>&1; then
  echo "Installing Java 21..."
  apt-get update -qq
  apt-get install -y --no-install-recommends openjdk-21-jre-headless
fi

# ── PostgreSQL ────────────────────────────────────────────────────────────────

if ! dpkg -l postgresql &>/dev/null 2>&1; then
  echo "Installing PostgreSQL..."
  apt-get install -y --no-install-recommends postgresql
fi
systemctl enable --now postgresql

sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='ecr_harvester'" \
  | grep -q 1 || sudo -u postgres psql -c "CREATE DATABASE ecr_harvester"
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'postgres'" > /dev/null

# ── Extract JARs from ghcr.io images ─────────────────────────────────────────
# Pull image → copy JAR out → delete image immediately to free space.

mkdir -p /opt/ecr
cp .env /opt/ecr/.env

extract_jar() {
  local name=$1
  echo "Pulling $name..."
  docker pull --quiet "ghcr.io/dziesio/$name:latest"
  local cid
  cid=$(docker create "ghcr.io/dziesio/$name:latest")
  docker cp "$cid:/app/app.jar" "/opt/ecr/$name.jar"
  docker rm "$cid" > /dev/null
  docker rmi "ghcr.io/dziesio/$name:latest" > /dev/null
  echo "  → /opt/ecr/$name.jar"
}

extract_jar ecr-harvester
extract_jar ecr-api
extract_jar ecr-frontend
extract_jar ecr-notifier

# ── Systemd unit files (written once) ────────────────────────────────────────

if [ ! -f /etc/systemd/system/ecr-harvester.service ]; then
  cat > /etc/systemd/system/ecr-harvester.service <<'UNIT'
[Unit]
Description=ECR Harvester
After=postgresql.service
Requires=postgresql.service

[Service]
Type=simple
WorkingDirectory=/opt/ecr
EnvironmentFile=/opt/ecr/.env
Environment=SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ecr_harvester
Environment=SPRING_DATASOURCE_USERNAME=postgres
Environment=SPRING_DATASOURCE_PASSWORD=postgres
Environment=JAVA_TOOL_OPTIONS=-Xms64m -Xmx180m -XX:+UseG1GC
ExecStart=/usr/bin/java -jar /opt/ecr/ecr-harvester.jar
Restart=on-failure
RestartSec=30
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

  cat > /etc/systemd/system/ecr-api.service <<'UNIT'
[Unit]
Description=ECR API
After=postgresql.service
Requires=postgresql.service

[Service]
Type=simple
WorkingDirectory=/opt/ecr
Environment=SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ecr_harvester
Environment=SPRING_DATASOURCE_USERNAME=postgres
Environment=SPRING_DATASOURCE_PASSWORD=postgres
Environment=JAVA_TOOL_OPTIONS=-Xms32m -Xmx120m -XX:+UseG1GC
ExecStart=/usr/bin/java -jar /opt/ecr/ecr-api.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

  cat > /etc/systemd/system/ecr-frontend.service <<'UNIT'
[Unit]
Description=ECR Frontend
After=ecr-api.service
Wants=ecr-api.service

[Service]
Type=simple
WorkingDirectory=/opt/ecr
Environment=SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ecr_harvester
Environment=SPRING_DATASOURCE_USERNAME=postgres
Environment=SPRING_DATASOURCE_PASSWORD=postgres
Environment=ECR_API_BASE_URL=http://localhost:8081
Environment=SERVER_PORT=40445
Environment=JAVA_TOOL_OPTIONS=-Xms32m -Xmx120m -XX:+UseG1GC
ExecStart=/usr/bin/java -jar /opt/ecr/ecr-frontend.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

  cat > /etc/systemd/system/ecr-notifier.service <<'UNIT'
[Unit]
Description=ECR Notifier
After=ecr-api.service
Wants=ecr-api.service

[Service]
Type=simple
WorkingDirectory=/opt/ecr
EnvironmentFile=/opt/ecr/.env
Environment=SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ecr_harvester
Environment=SPRING_DATASOURCE_USERNAME=postgres
Environment=SPRING_DATASOURCE_PASSWORD=postgres
Environment=ECR_API_BASE_URL=http://localhost:8081
Environment=JAVA_TOOL_OPTIONS=-Xms32m -Xmx96m -XX:+UseG1GC
ExecStart=/usr/bin/java -jar /opt/ecr/ecr-notifier.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

  systemctl daemon-reload
  systemctl enable ecr-harvester ecr-api ecr-frontend ecr-notifier
  echo "Systemd services registered."
fi

# ── Start/restart ─────────────────────────────────────────────────────────────

echo "Starting services..."
systemctl restart ecr-harvester

echo "Waiting for ecr-api to be ready..."
systemctl restart ecr-api
for i in $(seq 1 30); do
  curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1 && break || true
  sleep 2
done

systemctl restart ecr-frontend
systemctl restart ecr-notifier

echo ""
echo "Done. Status:"
systemctl status ecr-harvester ecr-api ecr-frontend ecr-notifier --no-pager \
  | grep -E "●|Active:"

echo ""
echo "Logs: journalctl -u ecr-harvester -f"
