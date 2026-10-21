#!/usr/bin/env bash
# Deletes every path listed in tools/removed-paths.txt from the module sources.
# Usage: tools/apply-removals.sh [module-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${1:-$ROOT/tomcat-secure-embed}"
LIST="$ROOT/tools/removed-paths.txt"

sed -e 's/#.*$//' -e 's/[[:space:]]*$//' "$LIST" | grep -v '^$' | while read -r path; do
  for base in "$MODULE/src/main/java" "$MODULE/src/main/resources"; do
    if [ -e "$base/$path" ]; then
      rm -rf "${base:?}/$path"
    fi
  done
done

# Remove now-empty directories
find "$MODULE/src/main" -type d -empty -delete
echo "Removals applied to $MODULE"
