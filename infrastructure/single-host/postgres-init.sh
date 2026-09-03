#!/bin/sh
# Sourced by the official entrypoint only for a NEW database volume.
set -eu
export KEYCLOAK_DB_PASSWORD="$(cat /run/secrets/keycloak_db_password)"
export APPLICATION_DB_PASSWORD="$(cat /run/secrets/postgres_password)"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv keycloak_password KEYCLOAK_DB_PASSWORD
\getenv app_password APPLICATION_DB_PASSWORD
CREATE ROLE guanxian LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
ALTER DATABASE guanxian OWNER TO guanxian;
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password';
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE guanxian FROM PUBLIC;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL
unset KEYCLOAK_DB_PASSWORD
unset APPLICATION_DB_PASSWORD
