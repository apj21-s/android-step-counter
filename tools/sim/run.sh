#!/bin/sh
# Simulated walking: old build vs new build. Needs a JDK (uses the Android Studio one by default).
set -e
J=${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11-1}/bin
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
"$J/javac" -d "$OUT" "$ROOT"/app/src/main/java/com/arco/stride/AccelPedometer.java "$ROOT"/app/src/main/java/com/arco/stride/StepFusion.java \
  "$ROOT"/tools/sim/old/*.java "$ROOT"/tools/sim/SimSuite.java
"$J/java" -cp "$OUT" com.arco.stride.SimSuite
rm -rf "$OUT"
