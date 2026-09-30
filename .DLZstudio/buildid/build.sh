#!/usr/bin/env bash
# IEMS standard build entry (Unix) - DLZstudio BUILDID system
#
# Usage:
#   ./build.sh            internal build -> IEMS-<mod_version>-BUILD.<8-digit>.jar
#   ./build.sh --release  release build  -> <mod_id>-<mc_version>-neoforge-<semver>.<n>.jar
#
# Release mode injects the BUILDID as the last segment of the mod version
# (semver.<n>), keeping the jar name and the version in neoforge.mods.toml in sync.
set -e

RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --release|-r) RELEASE=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDID_FILE="$DIR/BUILDID.txt"
CURRENT=$(cat "$BUILDID_FILE" | tr -d '[:space:]')
NEXT=$((10#$CURRENT + 1))
NEXT_PADDED=$(printf "%08d" "$NEXT")
printf "%s" "$NEXT_PADDED" > "$BUILDID_FILE"
BUILD_ID="BUILD.$NEXT_PADDED"
echo "==> IEMS build id: $BUILD_ID"

PROJECT_ROOT="$(cd "$DIR/../.." && pwd)"
PROPS="$PROJECT_ROOT/gradle.properties"
read_prop() {
  grep -E "^$1=" "$PROPS" | head -n1 | sed -E "s/^$1=//" | tr -d '\r'
}
MOD_ID="$(read_prop mod_id)"
SEMVER="$(read_prop mod_version)"

cd "$PROJECT_ROOT"
if [ "$RELEASE" -eq 1 ]; then
  MC_VERSION="$(read_prop minecraft_version)"
  RELEASE_VERSION="$SEMVER.$NEXT"
  echo "==> release version: $RELEASE_VERSION"
  ./gradlew build "-Pmod_version=$RELEASE_VERSION"
  JAR="build/libs/$MOD_ID-$RELEASE_VERSION.jar"
  if [ -f "$JAR" ]; then
    NEW_NAME="$MOD_ID-$MC_VERSION-neoforge-$RELEASE_VERSION.jar"
    mv "$JAR" "build/libs/$NEW_NAME"
    echo "==> artifact: $NEW_NAME"
  fi
else
  ./gradlew build
  JAR="build/libs/$MOD_ID-$SEMVER.jar"
  if [ -f "$JAR" ]; then
    NEW_NAME="IEMS-$SEMVER-$BUILD_ID.jar"
    mv "$JAR" "build/libs/$NEW_NAME"
    echo "==> artifact: $NEW_NAME"
  fi
fi