#!/usr/bin/env bash
#
# Deploys the files collected by collect-dist.sh into a local clone of the mvn-repo branch
# (tamacat/tamacat.github.io) and stages the change, then checks that the change only adds the new
# version (nothing deleted, nothing outside the two artifact directories, no version dropped from
# maven-metadata.xml). Does not commit or push.
#
# Exit codes: 0 = staged, 3 = version already published (nothing to do), other = error.
#
# Usage: tools/stage-mvn-repo.sh <dist-dir> <mvn-repo-clone> <version>
#
set -euo pipefail

DIST="${1:?usage: stage-mvn-repo.sh <dist-dir> <mvn-repo-clone> <version>}"
REPO="${2:?}"
V="${3:?}"
GROUP_PATH=org/tamacat
ARTIFACTS="tomcat-secure-parent tomcat-secure-embed"
DEPLOY=org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file

(cd "$DIST" && sha256sum -c --quiet SHA256SUMS)

# Released versions are immutable
for a in $ARTIFACTS; do
  if [ -e "$REPO/$GROUP_PATH/$a/$V" ]; then
    echo "$a $V is already published in mvn-repo"
    exit 3
  fi
done

REPO_ABS="$(cd "$REPO" && pwd)"
if command -v cygpath > /dev/null 2>&1; then
  REPO_ABS="/$(cygpath -m "$REPO_ABS")"  # Git Bash on Windows: /c/x -> /C:/x
fi
URL="file://$REPO_ABS"
mvn -B -q -N "$DEPLOY" -Durl="$URL" -DrepositoryId=mvn-repo \
  -DpomFile="$DIST/tomcat-secure-parent-$V.pom" -Dfile="$DIST/tomcat-secure-parent-$V.pom" -Dpackaging=pom
mvn -B -q -N "$DEPLOY" -Durl="$URL" -DrepositoryId=mvn-repo \
  -DpomFile="$DIST/tomcat-secure-embed-$V.pom" -Dfile="$DIST/tomcat-secure-embed-$V.jar" \
  -Dsources="$DIST/tomcat-secure-embed-$V-sources.jar"

git -C "$REPO" add -A
DIFF="$(mktemp)"
trap 'rm -f "$DIFF"' EXIT
git -C "$REPO" diff --cached --no-renames --name-status > "$DIFF"

VRE="$(printf '%s' "$V" | sed 's/[.]/[.]/g')"
allowed="^A	${GROUP_PATH}/(tomcat-secure-parent|tomcat-secure-embed)/${VRE}/[^/]+$"
meta="^[AM]	${GROUP_PATH}/(tomcat-secure-parent|tomcat-secure-embed)/maven-metadata[.]xml([.](md5|sha1|sha256|sha512))?$"
if grep -q '^D' "$DIFF" || grep -v -E "$allowed" "$DIFF" | grep -v -E "$meta" | grep -q .; then
  cat "$DIFF"
  echo "the change goes beyond the new version directories and maven-metadata.xml" >&2
  exit 1
fi
if git -C "$REPO" diff --cached -- "$GROUP_PATH/tomcat-secure-parent/maven-metadata.xml" \
     "$GROUP_PATH/tomcat-secure-embed/maven-metadata.xml" | grep -q '^-[[:space:]]*<version>'; then
  echo "maven-metadata.xml would drop a published version" >&2
  exit 1
fi
for a in $ARTIFACTS; do
  grep -q "<version>$V</version>" "$REPO/$GROUP_PATH/$a/maven-metadata.xml" ||
    { echo "$a/maven-metadata.xml does not list $V" >&2; exit 1; }
done
cat "$DIFF"
