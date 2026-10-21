#!/usr/bin/env bash
#
# Regenerates patches/tomcat-secure.patch: the difference between the upstream sources (after the removals in
# tools/removed-paths.txt) and the modified sources in tomcat-secure-embed/.
#
# Usage: tools/make-patch.sh [tomcat-version]   (default: tomcat.version from pom.xml)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-$(sed -n 's|.*<tomcat.version>\(.*\)</tomcat.version>.*|\1|p' "$ROOT/pom.xml")}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

bash "$ROOT/tools/import-tomcat.sh" "$VERSION" "$WORK/a" --no-patch >/dev/null

mkdir -p "$WORK/b/src/main"
(cd "$ROOT/tomcat-secure-embed/src/main" && tar -cf - --exclude=./java/org/tamacat --exclude=./resources/META-INF .) |
  (cd "$WORK/b/src/main" && tar -xf -)
rm -rf "$WORK/a/src/main/java/org/tamacat" "$WORK/a/src/main/resources/META-INF"

{
  echo "Security hardening patch for Apache Tomcat $VERSION embed sources (core + EL + Jasper + annotations API)."
  echo "Apply after the removals listed in tools/removed-paths.txt (see tools/import-tomcat.sh)."
  echo
  (cd "$WORK" && diff -ruN a b || [ $? -eq 1 ])
} > "$ROOT/patches/tomcat-secure.patch"

echo "patches/tomcat-secure.patch: $(grep -c '^+++ ' "$ROOT/patches/tomcat-secure.patch") files"
