#!/usr/bin/env bash
# 엔진 합성 검증을 Kotlin 컴파일러로 직접 실행 (Gradle 불필요). 사용: KOTLIN_ALL_JAR=... KOTLIN_STDLIB=... tools/run_engine_checks.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/dropmate-engine-out"
rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$KOTLIN_ALL_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -nowarn -classpath "$KOTLIN_STDLIB" -no-stdlib \
  -d "$OUT" $(find "$ROOT/engine/src/main/kotlin" "$ROOT/engine/src/test/kotlin" -name '*.kt' ! -name '*JUnit*') 2>&1 | grep -v -e '^Picked up' || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$KOTLIN_STDLIB" com.jiye.dropmate.engine.EngineChecksKt 2>&1 | grep -v '^Picked up'
