#!/usr/bin/env bash
# IEMS standard build entry (Unix) - DLZstudio BUILDID system
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDID_FILE="$DIR/BUILDID.txt"
CURRENT=$(cat "$BUILDID_FILE" | tr -d '[:space:]')
NEXT=$((10#$CURRENT + 1))
NEXT_PADDED=$(printf "%08d" "$NEXT")
printf "%s" "$NEXT_PADDED" > "$BUILDID_FILE"
BUILD_ID="BUILD.$NEXT_PADDED"
echo "==> IEMS build id: $BUILD_ID"

PROJECT_ROOT="$(dirname "$DIR")"
cd "$PROJECT_ROOT"
./gradlew build
JAR="build/libs/iems-0.8.0-beta.jar"
if [ -f "$JAR" ]; then
  NEW_NAME="IEMS-0.8.0-beta-$BUILD_ID.jar"
  mv "$JAR" "build/libs/$NEW_NAME"
  echo "==> artifact: $NEW_NAME"
fi
