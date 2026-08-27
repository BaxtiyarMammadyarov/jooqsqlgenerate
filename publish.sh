#!/usr/bin/env bash
#
# jooq-sql-generate — Maven publish scripti
#
# İstifadə:
#   ./publish.sh            → clean build + test, sonra Maven Central-a publish
#   ./publish.sh --local    → clean build + test, sonra yalnız local Maven-ə (~/.m2)
#   ./publish.sh --skip-test → testsiz publish (tövsiyə olunmur)
#
# Qeyd: build/test uğursuz olsa, publish İCRA OLUNMUR (set -e).
#       Central üçün ~/.gradle/gradle.properties-də mavenCentralUsername/Password
#       + signingPassword olmalıdır.

set -euo pipefail
cd "$(dirname "$0")"

# ─── Versiyanı build faylından oxu ───────────────────────────────────────────
VERSION=$(grep -E '^version = ' build.gradle.kts | sed -E 's/version = "(.*)"/\1/')

# ─── Arqumentlər ──────────────────────────────────────────────────────────────
TASK="publishToMavenCentral"
TARGET="Maven Central"
RUN_TESTS=1

for arg in "$@"; do
  case "$arg" in
    --local)     TASK="publishToMavenLocal"; TARGET="local Maven (~/.m2)" ;;
    --skip-test) RUN_TESTS=0 ;;
    *) echo "Naməlum arqument: $arg"; echo "İstifadə: ./publish.sh [--local] [--skip-test]"; exit 1 ;;
  esac
done

echo "═══════════════════════════════════════════════════════════════"
echo "  jooq-sql-generate  v$VERSION  →  $TARGET"
echo "═══════════════════════════════════════════════════════════════"

# ─── 1) Build + test ──────────────────────────────────────────────────────────
if [[ "$RUN_TESTS" -eq 1 ]]; then
  echo "── 1/2  clean build + test ────────────────────────────────────"
  ./gradlew clean build test
  echo "     ✓ compile + testlər keçdi"
else
  echo "── 1/2  clean build (testsiz) ─────────────────────────────────"
  ./gradlew clean build -x test
  echo "     ✓ compile keçdi (testlər atlandı)"
fi

# ─── 2) Publish ───────────────────────────────────────────────────────────────
echo "── 2/2  $TASK ─────────────────────────────"
./gradlew "$TASK"

echo "═══════════════════════════════════════════════════════════════"
echo "  ✓ v$VERSION  →  $TARGET  tamamlandı"
if [[ "$TASK" == "publishToMavenCentral" ]]; then
  echo "  Status: central.sonatype.com → Deployments (~10-30 dəq sonra görünür)"
fi
echo "═══════════════════════════════════════════════════════════════"
