# MarginTrace

MarginTrace is a manufacturing margin-attribution and variance-analysis application for financial and data analysts. It combines sales, purchasing, production, inventory-usage, cost and bill-of-materials records to trace which materials drive a product's cost difference between two periods, and to compare the production evidence behind that difference.

The project is a research prototype. Its results come from recorded data and specified, tested algorithms; AI-generated explanations are optional and never replace the numerical evidence.

## Main workflows

- **Data preparation**: import spreadsheet exports, archive the raw files in MinIO and load them into the PostgreSQL warehouse.
- **Dual-BOM analysis**: compare two periods, find abnormal cost branches, rebuild production trees from recorded material issues and compare them with balanced Fused Gromov-Wasserstein (FGW) optimal transport.
- **Report Studio**: fill management-report placeholders from validated, read-only queries and edit the document in ONLYOFFICE.
- **Settings**: configure the local llama.cpp connection, the report template, GPU preferences and the embedding model.

## Quick start

Requirements: Docker Engine with the Docker Compose plugin on Linux, or Docker Desktop in Linux container mode on macOS or Windows. Java, Maven and Node.js are not needed on the host.

```sh
cp .env.deploy.example .env.deploy
# Edit .env.deploy and set DB_PASSWORD, MINIO_SECRET_KEY (at least 8 characters)
# and ONLYOFFICE_JWT_SECRET (any long random string).
docker compose --env-file .env.deploy -f compose.deploy.yaml up -d --build
```

Then open `http://localhost:18080`. The first build downloads images and dependencies and can take several minutes. The database starts with sample data.

Some features need additional local services:

- Production-pair comparison needs the embedding model, which can be installed from **Settings → Embedding model**.
- AI explanations and Report Studio blueprints need a llama.cpp server, configured under **Settings**. Without it, the analysis still runs and the AI text is reported as unavailable.
- GPU acceleration is optional; all analysis runs on the CPU.

See [DEPLOYMENT.md](DEPLOYMENT.md) for the full deployment instructions, including the optional NVIDIA CUDA backend.

## Repository layout

| Path | Contents |
| --- | --- |
| `backend/` | Java 21 and Spring Boot backend: import pipeline, warehouse, analysis algorithms, reporting and settings |
| `frontend/` | React, TypeScript and Vite frontend |
| `backend/src/main/resources/cuda/`, `backend/src/main/resources/opencl/` | Native CUDA and OpenCL acceleration with JNI bindings |
| `docs/algorithms/` | Algorithm specifications and test cases |
| `benchmark/` | Java, CUDA and OpenCL benchmarks |
| `postgres/`, `minio/`, `scripts/` | Database image and backups, MinIO image, utility scripts |

## Running the checks

The commands below run in Docker from the repository root.

```sh
# Backend tests, formatting check and packaging
docker run --rm -v "$PWD/backend:/workspace" -w /workspace maven:3.9-eclipse-temurin-21 \
  ./mvnw --batch-mode --no-transfer-progress clean verify

# Frontend type check and production build
docker build --target build -t margin-frontend-check -f frontend/Dockerfile frontend
```

Frontend unit tests run with Node.js 22 from `frontend/`, after `npm ci`: `node --experimental-strip-types --test src/*.test.ts`. Formatting is checked with `npm run format:check` (frontend) and Spotless (backend, part of `verify`).

## Licence

See [LICENSE](LICENSE).
