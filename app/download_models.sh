#!/bin/bash

MODEL_DIR="src/main/assets/models"
MODEL_NAMES=("ggml-small-q8_0.bin" "ggml-small-q5_1.bin" "ggml-base-q8_0.bin" "ggml-base-q5_1.bin" "ggml-tiny-q8_0.bin" "ggml-tiny-q5_1.bin" "ggml-medium-q5_0.bin" "ggml-medium-q8_0.bin")
BASE_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

mkdir -p "$MODEL_DIR"
cd "$MODEL_DIR"

for MODEL_NAME in "${MODEL_NAMES[@]}"
do
    if [ -f "$MODEL_NAME" ]; then
        echo "✅ $MODEL_NAME already exists. Skipping."
    else
        echo "⬇️ Downloading $MODEL_NAME..."
        curl -L -O "$BASE_URL/$MODEL_NAME"
        if [ $? -eq 0 ]; then
            echo "✅ Download complete: $MODEL_NAME"
        else
            echo "❌ Failed to download: $MODEL_NAME"
            exit 1
        fi
    fi
done
