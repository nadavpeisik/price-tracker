#!/bin/bash
# Creates the SELECT-only role Grafana connects as (#242). Grafana does not validate
# query safety, so the datasource must not hold credentials that can write.
#
# The postgres image runs this only when the data directory is initialized (first
# boot of a fresh volume). An EXISTING dev database never re-runs it — apply it
# once by hand instead (the container already carries the env vars):
#
#   docker compose exec postgres bash /docker-entrypoint-initdb.d/create-grafana-role.sh
#
# Idempotent: safe to re-run; re-running also resets the password to the current
# GRAFANA_DB_PASSWORD.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
  -v grafana_password="${GRAFANA_DB_PASSWORD:?GRAFANA_DB_PASSWORD not set}" \
  -v db="$POSTGRES_DB" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
SELECT format('CREATE ROLE grafana_reader LOGIN PASSWORD %L', :'grafana_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_reader')
\gexec
ALTER ROLE grafana_reader WITH PASSWORD :'grafana_password';
GRANT CONNECT ON DATABASE :"db" TO grafana_reader;
GRANT USAGE ON SCHEMA public TO grafana_reader;
-- Existing tables (no-op on first boot — Flyway runs later, at app startup) …
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader;
-- … and every table future migrations create as the application user.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO grafana_reader;
EOSQL
