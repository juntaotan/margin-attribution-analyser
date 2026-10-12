#!/usr/bin/env bash
# Check CDI access, remember CUDA mode, then rebuild only the deployed backend.
# Run against a reviewed deployment environment; backend startup runs Flyway.
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$task_root"
task_env=${1:-.env.deploy}
if [[ ! -f "$task_env" ]]; then
  echo "Deployment environment file not found: $task_env" >&2
  exit 1
fi
task_compose=(docker compose --env-file "$task_env" -f compose.deploy.yaml)
export BACKEND_RUNTIME=cuda
"${task_compose[@]}" config --quiet
# No database or storage service is started by this prerequisite check.
docker run --rm --device nvidia.com/gpu=all \
  nvidia/cuda:12.8.1-runtime-ubuntu24.04 nvidia-smi
# Preserve the rest of the environment file, including its permissions.
task_tmp=$(mktemp "${task_env}.XXXXXX")
trap 'rm -f "$task_tmp"' EXIT
awk '!/^[[:space:]]*(export[[:space:]]+)?BACKEND_RUNTIME[[:space:]]*=/' "$task_env" > "$task_tmp"
printf '\nBACKEND_RUNTIME=cuda\n' >> "$task_tmp"
chmod --reference="$task_env" "$task_tmp"
mv "$task_tmp" "$task_env"
echo "Saved BACKEND_RUNTIME=cuda; ordinary deployment updates will retain CUDA."
"${task_compose[@]}" build backend
"${task_compose[@]}" up -d --no-deps backend
"${task_compose[@]}" ps backend
