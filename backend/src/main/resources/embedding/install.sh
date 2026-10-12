#!/bin/sh
# Explicit, user-confirmed installation of the local embedding runtime and pinned model.
# Usage: install.sh <cpu|cuda> <runtime-dir> <requirements.txt> <sentence_encoder.py> <model-id> <revision>
# Progress lines start with "STAGE "; installed.json is written only after the model loads.
set -eu
variant=$1; dir=$2; requirements=$3; encoder=$4; model=$5; revision=$6
torch_version=2.14.1
python="$dir/venv/bin/python"
rm -f "$dir/installed.json"

echo "STAGE Creating Python environment"
python3 -m venv --clear "$dir/venv"

echo "STAGE Installing PyTorch ($variant)"
if [ "$variant" = cpu ]; then
  "$python" -m pip install --quiet --no-cache-dir --index-url https://download.pytorch.org/whl/cpu "torch==$torch_version"
else
  "$python" -m pip install --quiet --no-cache-dir "torch==$torch_version"
fi

echo "STAGE Installing sentence-transformers"
"$python" -m pip install --quiet --no-cache-dir -r "$requirements"

echo "STAGE Downloading model $model@$revision"
"$python" -c 'import sys; from sentence_transformers import SentenceTransformer; SentenceTransformer(sys.argv[1], revision=sys.argv[2], device="cpu")' "$model" "$revision"

echo "STAGE Verifying model"
result=$(printf '{"operation":"init","config":{"modelId":"%s","revision":"%s","backend":"CPU","device":null,"allowCpuFallback":false,"localOnly":true}}\n' "$model" "$revision" \
  | HF_HUB_OFFLINE=1 "$python" "$encoder")
case $result in *'"error"'*) echo "$result"; exit 1 ;; esac
cuda=$("$python" -c 'import torch; print(str(torch.cuda.is_available()).lower())')
printf '{"variant":"%s","modelId":"%s","revision":"%s","cudaAvailable":%s}\n' "$variant" "$model" "$revision" "$cuda" > "$dir/installed.json"
echo "STAGE Ready"
