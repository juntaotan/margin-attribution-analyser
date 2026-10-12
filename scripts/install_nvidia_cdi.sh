#!/usr/bin/env bash
# Install NVIDIA's CDI tools on an apt-based Linux host with an existing driver.
# Requires root. Does not change Docker configuration or restart Docker.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run this script with sudo in a host terminal." >&2
  exit 1
fi
command -v apt-get >/dev/null
nvidia-smi >/dev/null
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey -o "$task_tmp/key"
gpg --batch --dearmor --output "$task_tmp/keyring.gpg" "$task_tmp/key"
curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  -o "$task_tmp/repository.list"
sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
  "$task_tmp/repository.list" > "$task_tmp/signed-repository.list"
install -m 644 "$task_tmp/keyring.gpg" /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
install -m 644 "$task_tmp/signed-repository.list" /etc/apt/sources.list.d/nvidia-container-toolkit.list
apt-get update
apt-get install -y --no-install-recommends \
  "nvidia-container-toolkit-base=${NVIDIA_CONTAINER_TOOLKIT_VERSION:-1.20.1-1}"
# Toolkit 1.18+ refreshes the generated CDI specification at boot and driver updates.
systemctl restart nvidia-cdi-refresh.service
nvidia-ctk cdi list
