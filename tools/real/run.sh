#!/bin/sh
# Real labelled walks. Data: run convert.py on the Cambridge ubicomp13 dataset first.
# Usage: tools/real/run.sh <dir with index.csv> [-v]
set -e
J=${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11-1}/bin
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
"$J/javac" -d "$OUT" $(ls "$ROOT"/app/src/main/java/com/arco/stride/*.java | grep -E "/(StepFusion|MotionCounter|Placement|Buckets$( [ -f "$ROOT"/app/src/main/java/com/arco/stride/StepNetWeights.java ] && echo "|StepNet|StepNetWeights"))\.java$") "$ROOT"/tools/real/*.java "$ROOT"/tools/sim/old/AccelPedometer.java
"$J/java" -cp "$OUT" com.arco.stride.${MAIN:-RealSuite} "$@"
rm -rf "$OUT"
