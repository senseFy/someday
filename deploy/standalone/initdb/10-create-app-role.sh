#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${SOMEDAY_DB_USER:?SOMEDAY_DB_USER is required}"
: "${SOMEDAY_DB_PASSWORD:?SOMEDAY_DB_PASSWORD is required}"

if [ "$SOMEDAY_DB_USER" = "$POSTGRES_USER" ]; then
    echo "SOMEDAY_DB_USER must differ from the PostgreSQL administrator." >&2
    exit 1
fi

psql \
    --set=ON_ERROR_STOP=1 \
    --set=app_database="$POSTGRES_DB" \
    --set=app_user="$SOMEDAY_DB_USER" \
    --set=app_password="$SOMEDAY_DB_PASSWORD" \
    --username="$POSTGRES_USER" \
    --dbname="$POSTGRES_DB" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format('ALTER DATABASE %I OWNER TO %I', :'app_database', :'app_user')
\gexec
SQL
