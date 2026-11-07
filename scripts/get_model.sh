#!/usr/bin/env bash
# Download the AKKHARA-MINI ONNX model (int8) that the Android build packs into the app.
#
#   hf auth login            # once, after your access request on Hugging Face was approved
#   bash scripts/get_model.sh
#
# Result: models/akkhara-mini/onnx/{encoder.int8.onnx, decoder.int8.onnx, tokenizer.json}
# (android/app/build.gradle.kts copies them into the app's assets before every build)
set -euo pipefail
cd "$(dirname "$0")/.."
command -v hf >/dev/null || { echo "Install the Hugging Face CLI first: pip install -U huggingface_hub"; exit 1; }
hf download INGMUYLEANG/akkhara-mini \
  onnx/encoder.int8.onnx onnx/decoder.int8.onnx onnx/tokenizer.json \
  --local-dir models/akkhara-mini
ls -la models/akkhara-mini/onnx
echo "Model ready. Now: cd android && ./gradlew assembleDebug"
