#!/usr/bin/env bash
#
# Collects the Maven files to publish (after `mvn verify`) into <dist-dir> and writes SHA256SUMS.
# Prints the project version on stdout.
#
# Usage: tools/collect-dist.sh <dist-dir>
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:?usage: collect-dist.sh <dist-dir>}"

pom_value() { # <pom> <xpath relative to project>
  python3 - "$1" "$2" <<'PY'
import sys, xml.etree.ElementTree as E
n = {'m': 'http://maven.apache.org/POM/4.0.0'}
e = E.parse(sys.argv[1]).getroot().find(sys.argv[2], n)
print(e.text.strip() if e is not None else '')
PY
}

V="$(pom_value "$ROOT/pom.xml" m:version)"
MODULE_PARENT_V="$(pom_value "$ROOT/tomcat-secure-embed/pom.xml" m:parent/m:version)"
MODULE_V="$(pom_value "$ROOT/tomcat-secure-embed/pom.xml" m:version)"

if [ -z "$V" ] || [ "$MODULE_PARENT_V" != "$V" ] || { [ -n "$MODULE_V" ] && [ "$MODULE_V" != "$V" ]; }; then
  echo "version mismatch: parent=$V module.parent=$MODULE_PARENT_V module=$MODULE_V" >&2
  exit 1
fi
case "$V" in
  *-SNAPSHOT) echo "refusing to publish a SNAPSHOT version ($V)" >&2; exit 1 ;;
esac

T="$ROOT/tomcat-secure-embed/target"
mkdir -p "$DIST"
cp "$ROOT/pom.xml" "$DIST/tomcat-secure-parent-$V.pom"
cp "$ROOT/tomcat-secure-embed/pom.xml" "$DIST/tomcat-secure-embed-$V.pom"
cp "$T/tomcat-secure-embed-$V.jar" "$T/tomcat-secure-embed-$V-sources.jar" "$DIST/"
(cd "$DIST" && sha256sum -- * > SHA256SUMS)
echo "$V"
