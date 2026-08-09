FROM postgres:17

RUN apt-get update \
    && apt-get install --yes --no-install-recommends pgbackrest \
    && rm -rf /var/lib/apt/lists/* \
    && install --directory --owner=postgres --group=postgres --mode=0750 \
        /var/log/pgbackrest \
        /var/spool/pgbackrest
