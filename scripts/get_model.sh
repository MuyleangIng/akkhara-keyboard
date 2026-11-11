#!/usr/bin/env bash
# Download the AKKHARA-MINI ONNX model (int8, ~18.5 MB) that the Android build packs into the app.
# The model is open on Hugging Face: no account or login needed.
#
#   bash scripts/get_model.sh
#
# Result: models/akkhara-mini/onnx/{encoder.int8.onnx, decoder.int8.onnx, tokenizer.json}
# (android/app/build.gradle.kts copies them into the app's assets before every build)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p models/akkhara-mini/onnx
base="https://huggingface.co/muyleanging/Akkhara-Mini-Khmer-ONNX/resolve/main/onnx"
for f in encoder.int8.onnx decoder.int8.onnx tokenizer.json abbreviations.tsv; do
  curl -fL --progress-bar -o "models/akkhara-mini/onnx/$f" "$base/$f"
done
ls -la models/akkhara-mini/onnx
echo "Model ready. Now: cd android && ./gradlew assembleDebug"
