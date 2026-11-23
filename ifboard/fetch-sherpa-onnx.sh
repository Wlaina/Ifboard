#!/bin/bash
# 下载离线语音识别库（k2-fsa/sherpa-onnx 官方 GitHub Release），校验 SHA-256 后放到 app/libs/（已被 .gitignore 忽略）
set -e
cd "$(dirname "$0")/.."
VER=2.0.1
SHA=a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7
OUT=app/libs/sherpa-onnx-$VER.aar
if [ -f "$OUT" ] && echo "$SHA  $OUT" | sha256sum -c --status; then exit 0; fi
mkdir -p app/libs
curl -fL --retry 3 -o "$OUT.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
echo "$SHA  $OUT.part" | sha256sum -c -
mv "$OUT.part" "$OUT"
