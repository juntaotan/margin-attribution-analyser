#!/bin/sh

set -eu

schedule_state_path=${BACKUP_SCHEDULER_STATE_PATH:-/var/lib/pgbackrest-scheduler}

# A new named volume is initially owned by root. Prepare it once, then run all
# backup work as the same unprivileged user that owns the PostgreSQL data.
if [ "$(id -u)" -eq 0 ]; then
    install --directory --owner=postgres --group=postgres --mode=0750 "$schedule_state_path"
    exec gosu postgres "$0" "$@"
fi

schedule_timezone=${BACKUP_SCHEDULE_TIME_ZONE:-Pacific/Auckland}
full_day_of_week=${BACKUP_FULL_DAY_OF_WEEK:-7}
full_backup_time=${BACKUP_FULL_TIME:-02:00}
diff_backup_time=${BACKUP_DIFF_TIME:-02:00}
poll_seconds=${BACKUP_SCHEDULER_POLL_SECONDS:-60}

validate_time() {
    schedule_value=$1
    schedule_name=$2

    case "$schedule_value" in
        [0-2][0-9]:[0-5][0-9]) ;;
        *)
            echo "$schedule_name must use HH:MM format" >&2
            exit 1
            ;;
    esac

    schedule_hour=${schedule_value%:*}
    if [ "$schedule_hour" -gt 23 ]; then
        echo "$schedule_name hour must be between 00 and 23" >&2
        exit 1
    fi
}

case "$full_day_of_week" in
    [1-7]) ;;
    *)
        echo "BACKUP_FULL_DAY_OF_WEEK must be between 1 and 7" >&2
        exit 1
        ;;
esac

case "$poll_seconds" in
    ''|*[!0-9]*|0)
        echo "BACKUP_SCHEDULER_POLL_SECONDS must be a positive integer" >&2
        exit 1
        ;;
esac

validate_time "$full_backup_time" BACKUP_FULL_TIME
validate_time "$diff_backup_time" BACKUP_DIFF_TIME

if [ "$schedule_timezone" != UTC ] && [ ! -f "/usr/share/zoneinfo/$schedule_timezone" ]; then
    echo "Unknown BACKUP_SCHEDULE_TIME_ZONE: $schedule_timezone" >&2
    exit 1
fi

export TZ=$schedule_timezone

log_message() {
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1"
}

run_backup_if_due() {
    backup_type=$1
    backup_time=$2
    backup_date=$3
    success_file="$schedule_state_path/$backup_type.last-success"
    current_hhmm=$(date +%H%M)
    scheduled_hhmm="${backup_time%:*}${backup_time#*:}"
    last_success_date=

    if [ -f "$success_file" ]; then
        IFS= read -r last_success_date < "$success_file" || true
    fi

    if [ "$current_hhmm" -ge "$scheduled_hhmm" ] && [ "$last_success_date" != "$backup_date" ]; then
        log_message "Starting $backup_type backup for stanza $PGBACKREST_STANZA"

        if pgbackrest --type="$backup_type" backup; then
            printf '%s\n' "$backup_date" > "$success_file"
            log_message "Completed $backup_type backup for stanza $PGBACKREST_STANZA"
        else
            log_message "Failed $backup_type backup for stanza $PGBACKREST_STANZA; retrying later"
        fi
    fi
}

log_message "Backup scheduler started: full on ISO weekday $full_day_of_week at $full_backup_time, diff on other days at $diff_backup_time ($schedule_timezone)"

while :; do
    current_date=$(date +%F)
    current_day_of_week=$(date +%u)

    if [ "$current_day_of_week" -eq "$full_day_of_week" ]; then
        run_backup_if_due full "$full_backup_time" "$current_date"
    else
        run_backup_if_due diff "$diff_backup_time" "$current_date"
    fi

    sleep "$poll_seconds"
done
