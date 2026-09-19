# Single-host Docker deployment (demo)

For a step-by-step explanation of the design and configuration, read the [deployment guide](DEPLOYMENT_GUIDE.md).

This stack runs the web app, Java backend, PostgreSQL, and MinIO in Linux containers. On Ubuntu, install Docker Engine and the Docker Compose plugin. On macOS or Windows, use Docker Desktop in Linux container mode. The frontend and backend are compiled while their images are built, so Java, Maven, and Node.js do not need to be installed on the host.

1. Copy the project to the host. From the project root, create the deployment environment file. In a Linux, macOS, or WSL shell:

   ```sh
   cp .env.deploy.example .env.deploy
   chmod 600 .env.deploy
   ```

   In Windows PowerShell, use `Copy-Item .env.deploy.example .env.deploy` instead. `chmod` is a Unix command and is not required for this PowerShell step.

2. Edit `.env.deploy`. Set non-empty values for at least `DB_PASSWORD` and `MINIO_SECRET_KEY`. `APP_PORT` controls the host port for the web app and defaults to `8080`.

3. Start the stack:

   ```sh
   docker compose --env-file .env.deploy -f compose.deploy.yaml up -d --build
   docker compose --env-file .env.deploy -f compose.deploy.yaml ps
   ```

4. Open `http://localhost:8080` on the host, or `http://HOST_IP:8080` from another device that can reach it. If you changed `APP_PORT`, use that port instead. Inspect backend startup and database migration logs with:

   ```sh
   docker compose --env-file .env.deploy -f compose.deploy.yaml logs --tail=100 backend
   ```

The first build requires access to the container registries, Maven Central, and the npm registry. PostgreSQL and MinIO use separate named Docker volumes, so their data survives container recreation and host restarts. Only the web service publishes a host port. Nginx forwards `/api/` and `/imports` requests to the backend.

Flyway runs when the backend starts. Its `V3__sample_topology_data.sql` migration inserts sample data, so this configuration is intended for deployment practice. Address sample data and off-host backups before long-term use. Do not run `docker compose down -v` against data you want to keep: `-v` removes the named volumes.
