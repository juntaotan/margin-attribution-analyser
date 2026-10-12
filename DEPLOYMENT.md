# Single-host Docker deployment (demo)

For a step-by-step explanation of the design and configuration, read the [deployment guide](DEPLOYMENT_GUIDE.md).

This stack runs the web app, Java backend, PostgreSQL, and MinIO in Linux containers. On Ubuntu, install Docker Engine and the Docker Compose plugin. On macOS or Windows, use Docker Desktop in Linux container mode. The frontend and backend are compiled while their images are built, so Java, Maven, and Node.js do not need to be installed on the host.

1. Copy the project to the host. From the project root, create the deployment environment file. In a Linux, macOS, or WSL shell:

   ```sh
   cp .env.deploy.example .env.deploy
   chmod 600 .env.deploy
   ```

   In Windows PowerShell, use `Copy-Item .env.deploy.example .env.deploy` instead. `chmod` is a Unix command and is not required for this PowerShell step.

2. Edit `.env.deploy`. Set non-empty values for at least `DB_PASSWORD` and `MINIO_SECRET_KEY`. `APP_PORT` controls the host port for the web app and defaults to `18080`.

3. Start the stack:

   ```sh
   docker compose --env-file .env.deploy -f compose.deploy.yaml up -d --build
   docker compose --env-file .env.deploy -f compose.deploy.yaml ps
   ```

4. Open `http://localhost:18080` on the host, or `http://HOST_IP:18080` from another device that can reach it. If you changed `APP_PORT`, use that port instead. Inspect backend startup and database migration logs with:

   ```sh
   docker compose --env-file .env.deploy -f compose.deploy.yaml logs --tail=100 backend
   ```

The first build requires access to the container registries, GitHub, the Go module proxy, Maven Central, and the npm registry. MinIO is built from the fixed upstream `RELEASE.2025-10-15T17-29-55Z` source tag using `minio/Dockerfile`; it does not pull the unavailable `quay.io/minio/minio` image. The upstream [community repository](https://github.com/minio/minio) is archived and distributes source only. This source build fixes image availability; it does not establish production readiness or ongoing upstream maintenance.

PostgreSQL and MinIO use separate named Docker volumes, so their data survives container recreation and host restarts. The web service and ONLYOFFICE publish host ports. Nginx forwards `/api/` and `/imports` requests to the backend.

Flyway runs when the backend starts. Its `V3__sample_topology_data.sql` migration inserts sample data, so this configuration is intended for deployment practice. Address sample data and off-host backups before long-term use. Do not run `docker compose down -v` against data you want to keep: `-v` removes the named volumes.

## Embedding model for production comparison

Production-pair comparison needs a local embedding model. Without it, every planned pair is rejected with `MISSING_EMBEDDINGS`. Install it from the web app: **Settings → Embedding model**, choose the inference runtime, then **Download and install**. After confirmation, the backend container downloads:

- CPU: PyTorch from `download.pytorch.org`, sentence-transformers from `pypi.org` and the model from `huggingface.co` (about 1.5 GB on disk).
- NVIDIA CUDA: PyTorch with CUDA libraries and sentence-transformers from `pypi.org`, and the model from `huggingface.co` (about 6 GB on disk). This PyTorch build uses CUDA 13 libraries and needs a correspondingly recent NVIDIA driver; Settings shows whether the CUDA device was detected. Use it with the CUDA backend (`BACKEND_RUNTIME=cuda` or `cuda-desktop`); in the CPU backend no GPU is visible and inference stays on the CPU.

The backend therefore needs outbound HTTPS access to those hosts; set the standard `HTTPS_PROXY` variable on the backend service if required. Files are stored in the `embedding_runtime` named volume and survive container recreation. The default model is `sentence-transformers/all-MiniLM-L6-v2` at a pinned revision; another 384-dimensional mean-pooling model can be selected with `EMBEDDING_MODEL` and `EMBEDDING_REVISION` on the backend service, followed by a reinstall. The virtual environment is tied to the backend image's Python version: reinstall from Settings after a base-image upgrade or after switching between the CPU and CUDA backends.

## Optional NVIDIA CUDA backend

The deployment remembers the backend host mode in `.env.deploy`:

```dotenv
BACKEND_RUNTIME=cpu
```

Choose `cpu` (the default), `cuda` for Linux NVIDIA CDI, or `cuda-desktop` for
Windows Docker Desktop/WSL2. `compose.deploy.yaml` selects the corresponding
packaging and device access from `compose.backend.yaml`, so ordinary builds and
container recreation retain the choice. Selecting CUDA in Settings chooses an
analysis preference; host mode supplies its native library and device access.
The CUDA image sets `MARGIN_NATIVE_LIBRARY_DIR` to its installed JNI libraries.

This route requires Linux, an existing compatible NVIDIA driver, Docker Engine
28.3+ with CDI enabled, and NVIDIA CDI tools. Docker enables CDI by default from
28.3; see [Docker's CDI configuration](https://docs.docker.com/reference/cli/dockerd/#configure-cdi-devices).
The minimal `nvidia-container-toolkit-base` package supplies CDI tools without
the legacy runtime hook; see [NVIDIA's CDI documentation](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/cdi-support.html).

On an apt-based host with systemd, install the tools once in a host terminal:

```sh
sudo bash scripts/install_nvidia_cdi.sh
```

The script uses NVIDIA's signed stable package repository and pins toolkit base
1.20.1-1; `NVIDIA_CONTAINER_TOOLKIT_VERSION` can select another compatible version.
It refreshes the CDI specification and does not restart Docker. For other hosts,
follow [NVIDIA's installation guide](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html).
Existing installations can refresh their device specification with
`sudo systemctl restart nvidia-cdi-refresh.service`.

Review the existing `.env.deploy` connection settings and pending Flyway migrations
before restarting the backend, then run:

```sh
bash scripts/start_cuda_backend.sh
curl --fail http://localhost:18080/api/v1/analysis/capabilities
```

Use the configured `APP_PORT` for the capability request. The startup script first
checks GPU access in a disposable container. If that fails, it leaves the deployed
backend running. After a successful check, it saves `BACKEND_RUNTIME=cuda` in
`.env.deploy`, builds the CUDA image and recreates
only the backend with `--no-deps`; database, object-storage, web and ONLYOFFICE
containers and volumes are retained. Native loading failures are cached by the
JVM, so replacing libraries requires a backend restart.

For subsequent backend updates, use the ordinary deployment command:

```sh
docker compose --env-file .env.deploy -f compose.deploy.yaml up -d --no-deps --build backend
```

The remembered mode also applies to the full-stack command at the beginning of
this guide. Change `BACKEND_RUNTIME` to `cpu` to explicitly return to CPU packaging.
Keep `.env.deploy` during updates; recreating it from the example resets the choice.
The old `compose.cuda.yaml` overlay remains compatible for existing commands but
is no longer needed after saving the mode. Verify CUDA devices and both stage
reports in the capability response, then run an
analysis with CUDA selected. Inventory and `nvidia-smi` alone do not verify
numerical execution. CUDA architecture defaults to 75 with portable PTX;
`CUDA_ARCHITECTURES` can override it for a compatible toolkit/device. The image
also contains the OpenCL bridge, but its availability depends on the vendor ICD
injected by the device runtime; verify that family separately before selecting it.

### Who needs the host tools?

GPU devices belong to the machine running the backend. People opening the web app
in a browser do not install drivers or container tools. CPU-only deployments use
the base Compose file and need no NVIDIA tooling. A Linux backend running inside
Docker needs host device integration such as the CDI setup above. A backend run
directly with Java needs its native libraries and GPU driver/runtime, but does
not need NVIDIA's container tools. The Linux shared-library packaging described
here does not establish support for running the backend directly on Windows.

### Windows with Docker Desktop

For NVIDIA CUDA, Docker Desktop supports GPU access to Linux containers using its
WSL2 backend. Install a supported Windows NVIDIA driver, update WSL with
`wsl --update`, and enable the WSL2 backend in Docker Desktop. See
[Docker's Windows GPU prerequisites](https://docs.docker.com/desktop/features/gpu/).
Do not run `install_nvidia_cdi.sh` for Docker Desktop: that script configures the
host Linux Docker Engine. Microsoft distinguishes Docker Desktop from an Engine
installed directly inside WSL, which needs its own container-toolkit setup; see
[Microsoft's WSL GPU guide](https://learn.microsoft.com/en-us/windows/wsl/tutorials/gpu-compute).

From the project directory in PowerShell, first check container GPU access:

```powershell
docker run --rm --gpus all nvidia/cuda:12.8.1-runtime-ubuntu24.04 nvidia-smi
```

After reviewing `.env.deploy` and pending migrations, set this value once:

```dotenv
BACKEND_RUNTIME=cuda-desktop
```

Then use the ordinary deployment command. The selected mode requests NVIDIA GPU
access rather than Linux CDI names:

```powershell
docker compose --env-file .env.deploy -f compose.deploy.yaml up -d --no-deps --build backend
```

The old Desktop overlay remains compatible for existing commands. Do not combine
the two GPU overlays. This Desktop configuration has passed Compose configuration checks;
Windows/WSL2 device and numerical execution have not been tested in this task.
