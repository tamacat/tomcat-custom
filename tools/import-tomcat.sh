#!/usr/bin/env bash
#
# Imports the Apache Tomcat embed sources of the given version from Maven Central, verifies their GPG signatures
# against the Apache Tomcat KEYS file, deletes everything listed in tools/removed-paths.txt and applies
# patches/tomcat-secure.patch.
#
# Usage:
#   tools/import-tomcat.sh <tomcat-version>                 # import into tomcat-secure-embed/ (overwrites Tomcat sources)
#   tools/import-tomcat.sh <tomcat-version> <dir> --no-patch # pristine (removals only) import into <dir>, used by make-patch.sh
#
# Files owned by this project are never touched:
#   src/main/java/org/tamacat/**, src/main/resources/META-INF/**
#
set -euo pipefail

VERSION="${1:?usage: import-tomcat.sh <tomcat-version> [target-dir] [--no-patch]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${2:-$ROOT/tomcat-secure-embed}"
APPLY_PATCH=yes
if [ "${3:-}" = "--no-patch" ]; then
  APPLY_PATCH=no
fi

MAVEN=https://repo.maven.apache.org/maven2/org/apache/tomcat
KEYS_URL=https://downloads.apache.org/tomcat/tomcat-${VERSION%%.*}/KEYS
ARTIFACTS="embed/tomcat-embed-core embed/tomcat-embed-el embed/tomcat-embed-jasper tomcat-annotations-api"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export GNUPGHOME="$WORK/gnupg"
mkdir -p "$GNUPGHOME" "$WORK/download" "$WORK/extract"
chmod 700 "$GNUPGHOME"

echo "Importing Apache Tomcat $VERSION sources"
curl -fsSL "$KEYS_URL" -o "$WORK/KEYS"
gpg --batch --quiet --import "$WORK/KEYS" 2>/dev/null

for artifact in $ARTIFACTS; do
  name="$(basename "$artifact")"
  jar="$name-$VERSION-sources.jar"
  curl -fsSL "$MAVEN/$artifact/$VERSION/$jar" -o "$WORK/download/$jar"
  curl -fsSL "$MAVEN/$artifact/$VERSION/$jar.asc" -o "$WORK/download/$jar.asc"
  if ! gpg --batch --verify "$WORK/download/$jar.asc" "$WORK/download/$jar" 2>"$WORK/gpg.log"; then
    cat "$WORK/gpg.log" >&2
    echo "GPG signature verification FAILED for $jar" >&2
    exit 1
  fi
  echo "  verified $jar ($(grep -o 'Good signature from "[^"]*"' "$WORK/gpg.log"))"
  mkdir -p "$WORK/extract/$name"
  unzip -q -o "$WORK/download/$jar" -d "$WORK/extract/$name"
done

# Stage: src/main/java (*.java) and src/main/resources (everything else except META-INF)
STAGE="$WORK/stage"
mkdir -p "$STAGE/src/main/java" "$STAGE/src/main/resources"
for dir in "$WORK"/extract/*; do
  (cd "$dir" && find . -type f -name '*.java' -print0 | tar --null -cf - -T - | tar -xf - -C "$STAGE/src/main/java")
  (cd "$dir" && find . -type f ! -name '*.java' ! -path './META-INF/*' -print0 | tar --null -cf - -T - |
    tar -xf - -C "$STAGE/src/main/resources")
done

# Normalise line endings
find "$STAGE/src" -type f \( -name '*.java' -o -name '*.properties' -o -name '*.xml' -o -name '*.xsd' -o \
  -name '*.dtd' -o -name '*.html' -o -name '*.jjt' \) -print0 | xargs -0 sed -i 's/\r$//'

bash "$ROOT/tools/apply-removals.sh" "$STAGE" >/dev/null
echo "  removals applied ($(grep -cv '^\s*\(#\|$\)' "$ROOT/tools/removed-paths.txt") entries)"

if [ "$APPLY_PATCH" = yes ]; then
  (cd "$STAGE" && patch -p1 -s --fuzz=0 --no-backup-if-mismatch < "$ROOT/patches/tomcat-secure.patch")
  echo "  patches/tomcat-secure.patch applied"
fi

# Replace the Tomcat sources in the target, keeping project owned files
mkdir -p "$TARGET/src/main/java" "$TARGET/src/main/resources"
for base in java resources; do
  find "$TARGET/src/main/$base" -mindepth 1 -maxdepth 1 ! -name org ! -name META-INF -exec rm -rf {} +
  if [ -d "$TARGET/src/main/$base/org" ]; then
    find "$TARGET/src/main/$base/org" -mindepth 1 -maxdepth 1 ! -name tamacat -exec rm -rf {} +
  fi
  (cd "$STAGE/src/main/$base" && tar -cf - .) | (cd "$TARGET/src/main/$base" && tar -xf -)
done
echo "Done: $TARGET"
