#!/usr/bin/env bash
# Clones whisper.cpp at the pinned tag into third_party/ (gitignored).
set -euo pipefail

VERSION="${WHISPER_VERSION:-v1.9.5}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/whisper.cpp"

if [ -d "$DEST/.git" ]; then
    current="$(git -C "$DEST" describe --tags --exact-match 2>/dev/null || echo unknown)"
    if [ "$current" = "$VERSION" ]; then
        echo "whisper.cpp already at $VERSION"
        exit 0
    fi
    echo "whisper.cpp at $current, want $VERSION - removing and refetching"
    rm -rf "$DEST"
fi

mkdir -p "$ROOT/third_party"
git clone --depth 1 --branch "$VERSION" https://github.com/ggml-org/whisper.cpp "$DEST"
echo "whisper.cpp $VERSION -> $DEST"
