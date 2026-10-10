# Project Instructions

These instructions apply to the `margin-attribution-analyser` repository.
Follow the user's explicit instructions for the current task when they differ from this file.

## Project Overview

MarginTrace is a manufacturing margin-attribution and variance-analysis application. It combines sales, purchasing, production, inventory-usage, cost, and bill-of-materials (BOM) records to trace material-to-product relationships and investigate differences in consumption and cost.

The intended users are financial analysts and data analysts in manufacturing businesses. This is an open-source project intended for use beyond the maintainer's own environment. The project is currently a prototype for research and validation. Prioritise verified analytical behaviour and explicit validation results; do not imply production readiness solely because a feature builds or a demonstration works.

The current application provides four main workflows:

- Data preparation: import source files, map their schemas, and load warehouse records while retaining raw files in object storage.
- Dual-BOM analysis: inspect planned BOM relationships and actual consumption, compare selected periods, and display graphs, reconciliation paths, and supporting evidence.
- Management reporting: prepare report templates, resolve data placeholders, and edit documents through ONLYOFFICE.
- Settings: configure the AI service connection used by analysis and reporting features.

Java and Spring Boot provide the complete business backend, including data access, graph construction, reconciliation, and reporting. The React frontend presents the workflows. PostgreSQL stores application and warehouse records; MinIO stores raw files and report objects. AI-assisted explanations are based on evidence computed by the backend; generated prose must not substitute for numerical evidence.

OpenCL and CUDA provide acceleration for selected analysis algorithms. Their integration with the business backend is not yet complete; the intended direction is to integrate these implementations into the backend. Keep business orchestration in Java and preserve the specified analytical behaviour when introducing accelerated implementations.

The project-level goal is for all analysis functionality to be available on the CPU, with GPU acceleration optional for core analysis. This describes the intended product architecture, not the current implementation status or a requirement to implement or validate both execution paths in every task. Apply CPU and GPU requirements to the work in scope, as directed by the user and the applicable specifications; do not expand a staged task to complete unrelated execution paths.

Production-instance tree restoration using FIFO and standard Fused Gromov-Wasserstein (FGW) comparison are specified in the production-analysis documents. Their presence in the specifications does not establish that they are already implemented or integrated. Check the code and validation results before describing a capability as complete.

## Working Approach

- Carry authorised tasks through implementation and validation. Do not stop at a proposal or a partial implementation when the user has asked for completed work.
- Make routine implementation decisions independently. Ask focused questions when missing information materially affects the required behaviour, and continue independent work while awaiting answers.
- Read the relevant project documents before changing behaviour. Complete every test and deliverable required by those documents for the task in scope.
- Inspect existing changes before editing. Preserve the user's work and unrelated changes; do not overwrite, revert, or include them in your own changes without authorisation.
- Preserve the existing structure and make the smallest changes needed to complete the task. Follow the surrounding code's conventions and avoid unsolicited refactoring.
- Leave completed and validated changes uncommitted. Create a Git commit only when the user explicitly requests it; completion or passing tests do not constitute permission to commit.
- Report what changed, which checks actually ran, their results, and any remaining limitations.

## Language

- Write all new or revised code comments, documentation, explanations in deliverable files, and written artefacts in English.
- Use British English for prose, for example: "behaviour", "initialisation", "optimisation", and "analyse".
- Use American English for variable names, for example: `initialized`, `optimizationResult`, and `analyzeRequest`.
- Preserve existing public identifiers, file paths, external API names, and quoted source text. Do not rename existing identifiers solely to change their spelling.
- Follow the user's requested language for conversation. Chinese conversation does not change the English requirement for project files and deliverables.

## Coding Conventions

- Follow the existing naming conventions: Java classes and React components use PascalCase; Java methods and variables use camelCase.
- Match new file and directory names to comparable existing modules. Do not perform bulk renaming solely to standardise naming or spelling.
- Write detailed documentation comments for important classes and public methods. Explain their purpose, inputs, outputs, and, where applicable, assumptions, side effects, and error behaviour. Use the language's documentation convention and the English requirements above.
- Prefer the project's existing dependencies and frameworks. Introduce a new dependency only when existing tools cannot reasonably meet the task's requirements, and explain the reason in the task record.
- Prefer changes compatible with existing API contracts, request and response fields, data formats, and database structures.
- When a task genuinely requires a contract, format, or schema change, update affected callers, documentation, and the prescribed tests in the same task.

## Project Structure

### Backend

`backend/` uses Java 21, Spring Boot, the Maven Wrapper, JPA, and Flyway. Java package paths below are relative to `backend/src/main/java/dev/margintrace/margin_attribution_backend/`:

| Path | Responsibility                                                                                                         |
| ------ | ------------------------------------------------------------------------------------------------------------------------ |
| `importation/`     | Import jobs, file validation, schema recognition and mapping, and warehouse-writing workflows.                         |
| `warehouse/`     | Business record entities and repositories for sales, purchases, production, inventory usage, costs, and BOMs.          |
| `datalake/`     | Raw-file storage, retrieval, object keys, and MinIO configuration.                                                     |
| `algorithm/TreeBuilder/`     | Date-scoped attribution workflow and CSR graph construction.                                                           |
| `algorithm/BOMFrontier/`     | Graph preparation, upward propagation, and reconciliation.                                                             |
| `algorithm/model/`     | Shared graph, node, edge, and propagation-path representations.                                                        |
| `analysis/`     | Analysis endpoints, request and response contracts, service orchestration, and AI-assisted root-cause reporting.       |
| `report/`     | Report templates, document validation, placeholders, data queries, AI-assisted blueprints, and ONLYOFFICE integration. |
| `settings/`     | Persisted AI connection settings and their management endpoints.                                                       |

Backend resources and tests:

- `backend/src/main/resources/db/migration/`: versioned Flyway migrations.
- `backend/src/main/resources/application.properties`: application configuration.
- `backend/src/main/resources/opencl/`: native OpenCL BOM propagation implementation and device tests.
- `backend/src/main/resources/cuda/`: native CUDA BOM propagation implementation and device tests; inspect the build configuration before assuming independent build support.
- `backend/src/test/java/`: Java tests, organised by feature and algorithm.
- `backend/src/test/resources/`: test configuration, including an H2 database with Flyway disabled.

### Frontend

`frontend/` uses React, TypeScript, Vite, and Tailwind CSS. Dependency versions are recorded in `frontend/package-lock.json`.

| Path | Responsibility                                                                                     |
| ------ | ---------------------------------------------------------------------------------------------------- |
| `frontend/src/App.tsx`     | Application navigation and top-level page selection.                                               |
| `frontend/src/DataPreparation.tsx`, `frontend/src/importApi.ts`, `frontend/src/fileRecognition.ts` | Import interface, API access, and file recognition.                                                |
| `frontend/src/components/DualBom/`     | Analysis controls, graph canvas, evidence panels, reconciliation paths, and material-ledger views. |
| `frontend/src/analysisGraph.ts`, `frontend/src/varianceEngine.ts`   | Graph and stream handling, and frontend variance calculations.                                     |
| `frontend/src/components/ReportStudio/`     | Report composition, templates, semantic parsing, and the ONLYOFFICE editor.                        |
| `frontend/src/components/Settings/`     | AI connection settings interface.                                                                  |
| `frontend/src/index.css`, `frontend/tailwind.config.js`   | Shared styles and Tailwind configuration.                                                          |
| `frontend/vite.config.ts`, `frontend/nginx.conf`   | Development proxy and deployed frontend routing.                                                   |

### Native Algorithms, Infrastructure, and Documentation

- `cuda/`: a standalone CUDA TreeBuilder static library with `include/` and `src/`. Its build currently has no Java backend linkage. Like the native OpenCL and CUDA modules under backend resources, it belongs to the analysis-acceleration work intended for future backend integration; do not assume that integration is already available.
- `benchmark/`: Java, CUDA, and OpenCL benchmarks, graph generators, loaders, and a benchmark runner. Performance measurements do not replace correctness tests.
- `postgres/`: PostgreSQL image customisation, backup scheduling, and point-in-time restoration support.
- `scripts/`: database restoration and material-overuse demonstration utilities; inspect their effects before execution.
- `compose.yaml`: PostgreSQL, backup, restoration, and MinIO infrastructure.
- `compose.dev.yaml`: development services and backend runtime settings.
- `compose.deploy.yaml`: containerised web application and supporting services.
- `Jenkinsfile`: backend verification and artefact archiving.
- `DEPLOYMENT.md`: deployment instructions.
- `docs/`: feature specifications and workflow documents; `docs/tasks/` holds task change and validation records.

## Task Specifications

- For FIFO production-tree restoration and FGW work, `docs/algorithms/Production Anomaly Analysis — Agent Workflow.md` and its three linked specifications below are the authoritative requirements and validation standards. Read the documents relevant to the requested stage and implement and test it as specified, subject to the user's explicit task instructions:

  - `docs/Agent Prompt_ Restore Product Production Trees Using FIFO.md`
  - `docs/algorithms/CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md`
  - `docs/algorithms/FGW CUDA Implementation — Numerical Test Case.md`
- Distinguish specified behaviour from existing implementation and proposed improvements. Surface conflicts rather than silently changing requirements.
- Treat implementation instructions in these documents as applying to their respective tasks; their presence alone is not an instruction to implement the entire workflow.

## Data and Calculation Rules

- Avoid binary floating-point precision loss in monetary calculations.
- Explicitly report missing inputs or incomplete allocations. Do not fabricate results.

## Validation

- Execute the tests explicitly required by the applicable project documents or the user's task instructions. Do not require additional regression tests beyond that scope. If a document requires creating automated tests, implement and run those tests.
- Docker is the default test environment for this project. Use the container commands below for Java and frontend checks; host commands are alternatives when the required host toolchain is intentionally available.
- Convert the applicable document requirements into a concrete validation checklist and run every required check before declaring the task complete.
- Preserve specified fixtures, expected results, tolerances, and algorithm definitions. Investigate failures instead of weakening assertions or changing expected values to fit the implementation.
- Fix failures caused by your changes in the required checks and distinguish unrelated existing failures. Do not broaden or repeat testing without a requirement or an unresolved failure that justifies it.
- When backend testing is required, use Java 21 and `./mvnw test` from `backend/`. The Jenkins verification command is `./mvnw --batch-mode --no-transfer-progress clean verify`; use it when the task requires full backend verification.
- When frontend build validation is required, use `npm run build` from `frontend/`. Use `npm ci` when dependencies need installing. There is currently no `test` or `lint` script in `frontend/package.json`; inspect existing test files and the available runtime instead of inventing commands or treating a build as a behavioural test.
- For OpenCL changes, follow `backend/src/main/resources/opencl/test/README.md`. From the repository root:

  ```sh
  cmake -S backend/src/main/resources/opencl -B backend/src/main/resources/opencl/build
  cmake --build backend/src/main/resources/opencl/build -j 4
  ctest --test-dir backend/src/main/resources/opencl/build --output-on-failure
  ```
- When the task changes OpenCL or CUDA execution, or an applicable specification requires device testing, run the prescribed device tests on a compatible device. If the required device, runtime, data, or service is unavailable, report the exact missing prerequisite and the checks left unverified. Do not report skipped or unrun tests as passing.
- When validating an accelerated implementation, compare it with the CPU reference using the prescribed fixtures and numerical tolerances where required by the applicable specification. A working CPU path does not replace GPU-specific tests required by that specification.
- For FGW numerical work, perform the prescribed intermediate checks, independent CPU comparison, and POT validation. Produce the required PASS/FAIL report and complete numerical correctness checks before GPU performance optimisation.
- Documentation-only edits need content and reference checks; application builds are unnecessary unless the documents explicitly require them.

### Docker Commands

These are execution references for checks required by the relevant documents or task; this command list does not independently require every check for every change.

The examples below use POSIX shell syntax, suitable for Bash, Zsh, or WSL. For PowerShell, use the same arguments on a single line and an appropriate checkout path. Run from the repository root on a machine whose Docker daemon can mount this checkout. For a remote Docker host, use a checkout on that host rather than assuming local paths are available there. Initial execution may download images and dependencies.

Backend tests, using Java 21 and the Maven Wrapper:

```sh
docker run --rm \
  -v "$PWD/backend:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-21 \
  ./mvnw --batch-mode --no-transfer-progress test
```

Full backend verification, matching the Jenkins lifecycle:

```sh
docker run --rm \
  -v "$PWD/backend:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-21 \
  ./mvnw --batch-mode --no-transfer-progress clean verify
```

Frontend dependency installation, TypeScript checking, and production build, using the existing Dockerfile build stage:

```sh
docker build --target build \
  -t margin-frontend-check \
  -f frontend/Dockerfile frontend
```

- These commands are derived from the existing Dockerfiles, Maven Wrapper, and Jenkins configuration; they have not yet been executed as part of preparing these instructions. Record actual results when running them for an implementation task.
- The backend Dockerfile skips tests during image packaging. A successful backend image build is not evidence that backend tests passed.
- The frontend build is a compilation and bundling check, not a substitute for required behavioural tests.
- When OpenCL testing is required for the task, run its documented CMake and CTest commands inside a suitably configured container with the required device and runtime available. A Java or Node container does not establish a GPU test environment.
- A reproducible CUDA test-container configuration remains to be established. Its current backend-resources CMake file also references benchmark source paths that do not exist relative to that module; inspect and resolve the build issue as part of CUDA implementation work rather than presenting an unverified CUDA command as ready to use.
- For required accelerator validation, determine device availability and compatible container runtimes in the user's actual environment at execution time. Do not bind test instructions to the maintainer's machine, and do not mark GPU validation complete until the prescribed tests have actually run in the required environment.

## Portability

- Design setup, configuration, and validation for other open-source users. Do not introduce dependencies on the maintainer's absolute paths, private hostnames or IP addresses, credentials, or particular GPU model.
- Keep environment-dependent paths, service addresses, and device selection configurable. Existing maintainer-specific configuration is not a template for new defaults.
- Document required dependencies, supported environments, and reproducible commands. Distinguish intended portability from platforms or devices that have actually been tested.
- When implementing or changing an analysis workflow, follow the project's CPU-availability goal within the scope and stages specified for that task. Do not treat this goal as a standing instruction to add CPU or GPU support to unrelated work.
- When a task uses or changes OpenCL or CUDA execution, check accelerator capabilities and make the selected execution path and any unavailable acceleration clear. Preserve specified results and numerical tolerances across CPU and accelerated implementations when both paths are in scope.

## Task Records

- For each task that changes project files, write a Markdown record in `docs/tasks/` after implementation and validation, before the final response. Documentation and configuration tasks also require a record.
- Use one file per task, named `YYYY-MM-DD-HHMMSS-short-task-description.md` using the task's start time and a concise English description. Update the same record when continuing the same task.
- Capture the start time when work begins and the end time after the implementation and validation finish. Use `Pacific/Auckland` local time, including the UTC offset. If a time was not captured, state that it is unknown rather than inventing it.
- Write the record in British English. Describe the actual changes, affected files, validation performed and results, and any remaining limitations or required checks that could not run.
- Keep the record specific to this task; do not attribute pre-existing or unrelated changes to your work. Writing the record does not create a separate task requiring another record.
- Use the following format, replacing the placeholders with actual information:

  ```text
  Start time: YYYY-MM-DD HH:MM:SS Pacific/Auckland (UTC+HH:MM)
  End time: YYYY-MM-DD HH:MM:SS Pacific/Auckland (UTC+HH:MM)
  Summary: Brief description of the completed work.
  Details:
  - Changes: What changed and why, including affected files.
  - Validation: Checks performed and their actual results.
  - Limitations: Remaining issues or unverified checks, or None.
  ```

## Data and Environment

- Check the actual connection settings before starting services or running data-writing commands. `compose.dev.yaml` currently configures the backend to use a remote PostgreSQL database despite also defining a local PostgreSQL service.
- The Vite development proxy currently targets backend port `8090`, while the development Compose backend publishes port `18080` by default. Check the intended setup before diagnosing connection failures.
- Use isolated test data and environments for tests that write data. Do not assume that a configuration labelled "dev" is isolated.
- Use a new Flyway migration for schema changes; do not rewrite an already-applied migration.
- Keep credentials out of source files, documentation, logs, and deliverables. Use the existing environment-variable configuration and example files.
- Preserve database and object-storage volumes. Do not delete volumes, restore over live data, or run destructive data utilities without explicit authorisation for that operation.
- Read `DEPLOYMENT.md` and the relevant configuration before deployment work; deployment instructions do not imply authorisation to deploy.
