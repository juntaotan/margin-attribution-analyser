#!/bin/sh

set -eu

restore_path=${PITR_RESTORE_PATH:-/var/lib/postgresql/restore}
target_time=${PITR_TARGET_TIME:-}
target_action=${PITR_TARGET_ACTION:-pause}

if [ -z "$target_time" ]; then
    echo "PITR_TARGET_TIME is required" >&2
    exit 1
fi

if ! printf '%s\n' "$target_time" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'; then
    echo "PITR_TARGET_TIME must be an ISO-8601 timestamp with an explicit timezone" >&2
    exit 1
fi

if ! normalized_target_time=$(date --date="$target_time" '+%Y-%m-%d %H:%M:%S.%N%:z' 2>/dev/null); then
    echo "PITR_TARGET_TIME is not a valid timestamp" >&2
    exit 1
fi

case "$target_action" in
    pause|promote) ;;
    *)
        echo "PITR_TARGET_ACTION must be pause or promote" >&2
        exit 1
        ;;
esac

if [ "$restore_path" != /var/lib/postgresql/restore ]; then
    echo "PITR_RESTORE_PATH must be /var/lib/postgresql/restore" >&2
    exit 1
fi

install --directory --mode=0700 "$restore_path"

if find "$restore_path" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
    echo "Restore target is not empty: $restore_path" >&2
    echo "Refusing to remove or overwrite an existing recovery attempt" >&2
    exit 1
fi

if [ "$(id -u)" -eq 0 ]; then
    chown postgres:postgres "$restore_path"
    exec gosu postgres "$0" "$@"
fi

echo "Restoring stanza $PGBACKREST_STANZA to $normalized_target_time with target action $target_action"

exec pgbackrest \
    --pg1-path="$restore_path" \
    --type=time \
    --target="$normalized_target_time" \
    --target-action="$target_action" \
    --target-timeline=latest \
    restore
