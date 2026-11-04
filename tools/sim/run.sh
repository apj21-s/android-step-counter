#!/bin/sh
# Simulated walking tests for the motion detector and the chip+motion combination.
# Usage: tools/sim/run.sh   (needs a JDK; uses the Android Studio one by default)
set -e
J=${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11-1}/bin
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
mkdir -p "$OUT/src/com/arco/stride"
cp "$ROOT"/app/src/main/java/com/arco/stride/AccelPedometer.java "$ROOT"/app/src/main/java/com/arco/stride/StepFusion.java "$ROOT"/tools/sim/*.java "$OUT/src/com/arco/stride/"
"$J/javac" -d "$OUT/bin" "$OUT"/src/com/arco/stride/*.java
"$J/java" -cp "$OUT/bin" com.arco.stride.PedoTest
for s in 1 2 3; do "$J/java" -cp "$OUT/bin" com.arco.stride.FusionTest $s; echo; done
rm -rf "$OUT"
