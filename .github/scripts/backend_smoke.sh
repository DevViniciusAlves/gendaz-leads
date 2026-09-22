#!/usr/bin/env bash
set -euo pipefail

POSTGRES_CONTAINER="gendaz-leads-smoke-postgres"
BACKEND_CONTAINER="gendaz-leads-smoke-backend"

cleanup() {
  docker rm -f "$BACKEND_CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d \
  --name "$POSTGRES_CONTAINER" \
  -e POSTGRES_DB=gendaz \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 55432:5432 \
  postgres:16-alpine

for i in $(seq 1 30); do
  if docker exec "$POSTGRES_CONTAINER" pg_isready -U postgres -d gendaz >/dev/null 2>&1; then
    break
  fi

  if [ "$i" -eq 30 ]; then
    echo "PostgreSQL smoke database did not become ready."
    docker logs "$POSTGRES_CONTAINER"
    exit 1
  fi

  sleep 1
done

docker build \
  -f backend/Dockerfile \
  backend \
  -t gendaz-leads-backend-smoke

docker run -d \
  --name "$BACKEND_CONTAINER" \
  --add-host=host.docker.internal:host-gateway \
  -p 18080:8080 \
  -e PORT=8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:55432/gendaz \
  -e DATABASE_USERNAME=postgres \
  -e DATABASE_PASSWORD=postgres \
  -e JWT_SECRET=smoke-test-secret-key-with-more-than-32-characters \
  -e GROQ_API_KEY=smoke-test \
  -e GROQ_ENABLED=false \
  -e OSM_CATALOG_DISCOVERY_ENABLED=true \
  -e OSM_CATALOG_ENABLED=true \
  -e OSM_SYNC_GITHUB_TOKEN= \
  gendaz-leads-backend-smoke

for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:18080/actuator/health >/tmp/gendaz-health.json 2>/dev/null; then
    cat /tmp/gendaz-health.json

    if grep -q '"status":"UP"' /tmp/gendaz-health.json; then
      echo "BACKEND_SMOKE_PASS"
      exit 0
    fi
  fi

  if ! docker ps --format '{{.Names}}' | grep -qx "$BACKEND_CONTAINER"; then
    echo "Backend container exited during startup."
    docker logs "$BACKEND_CONTAINER"
    exit 1
  fi

  sleep 2
done

echo "Backend did not become healthy."
docker logs "$BACKEND_CONTAINER"
exit 1