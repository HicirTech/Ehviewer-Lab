#!/usr/bin/env bash
# Runs the jcifs-ng write-semantics probe against a live share.
#
#   cp spike/smb-probe.properties.template spike/smb-probe.properties
#   $EDITOR spike/smb-probe.properties        # fill in share/user/pass
#   spike/run-probe.sh
#
# Jars come out of the Gradle cache, so the probe links against exactly the jcifs-ng the
# app ships (2.1.10) rather than whatever is on the system.
set -euo pipefail

cd "$(dirname "$0")/.."
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

find_jar() {
  find "$CACHE/$1" -name "$2" ! -name '*sources*' 2>/dev/null | head -1
}

JCIFS=$(find_jar eu.agno3.jcifs/jcifs-ng 'jcifs-ng-2.1.10.jar')
SLF4J=$(find_jar org.slf4j/slf4j-api 'slf4j-api-*.jar')
BCPROV=$(find_jar org.bouncycastle 'bcprov-*.jar')

for v in JCIFS SLF4J BCPROV; do
  if [ -z "${!v}" ]; then
    echo "could not locate $v jar under $CACHE" >&2
    exit 1
  fi
done

CP="$JCIFS:$SLF4J:$BCPROV"
echo "classpath:"
echo "  $JCIFS"
echo "  $SLF4J"
echo "  $BCPROV"
echo

exec java -cp "$CP" spike/JcifsProbe.java "${1:-spike/smb-probe.properties}"
