#!/bin/sh

set -eu

target_time=${1:-${PITR_TARGET_TIME:-}}
target_action=${PITR_TARGET_ACTION:-pause}

if [ -z "$target_time" ]; then
    echo "Usage: $0 <ISO-8601 target time with timezone>" >&2
    echo "Example: $0 '2026-08-09T12:34:56+12:00'" >&2
    exit 1
fi

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_directory=$(dirname -- "$script_directory")
cd "$project_directory"

echo "Restoring an isolated PostgreSQL cluster to $target_time"

PITR_TARGET_TIME=$target_time docker compose --profile pitr run --rm pgbackrest-restore
docker compose --profile pitr up --detach --no-deps postgres-pitr

attempt=0
until docker compose exec --no-TTY --user postgres postgres-pitr pg_isready -U postgres -d postgres >/dev/null 2>&1; do
    attempt=$((attempt + 1))

    if [ "$attempt" -ge 30 ]; then
        echo "The PITR validation instance did not become ready" >&2
        docker compose logs --no-color --tail=100 postgres-pitr >&2
        exit 1
    fi

    sleep 2
done

docker compose exec --no-TTY --user postgres postgres-pitr \
    psql -U postgres -d postgres -c \
    "select pg_is_in_recovery() as in_recovery, pg_last_xact_replay_timestamp() as last_replayed_transaction;"

echo "PITR validation instance is available on 127.0.0.1:${PITR_PORT:-5433}"

if [ "$target_action" = pause ]; then
    echo "The restored cluster remains isolated from the primary and paused in read-only recovery."
else
    echo "The restored cluster remains isolated from the primary and was promoted as requested."
fi
