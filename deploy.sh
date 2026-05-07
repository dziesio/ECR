#!/bin/bash
set -e
cd ~/ecr

echo "Pulling latest images..."
docker compose -f docker-compose.prod.yml pull

echo "Starting postgres..."
docker compose -f docker-compose.prod.yml up -d postgres

echo "Waiting for postgres to be healthy..."
until docker inspect ecr-postgres --format='{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; do
  sleep 2
done

echo "Starting ecr-harvester (first scrape)..."
docker compose -f docker-compose.prod.yml up -d ecr-harvester

echo "Waiting 3 minutes for first scrape to complete..."
sleep 180

echo "Starting remaining services..."
docker compose -f docker-compose.prod.yml up -d ecr-api ecr-frontend ecr-notifier

echo "Done. Status:"
docker compose -f docker-compose.prod.yml ps
