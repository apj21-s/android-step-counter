#!/bin/sh
# Phone -> laptop, in one command (phone on USB with USB debugging on).
#   tools/phone/sync.sh             pull new Lab recordings, score each one through the app's own code
#   tools/phone/sync.sh --train     ...then test the step model on your walks; new weights only if it's better
#   tools/phone/sync.sh --install   ...then build the APK and install it on the phone
# Data lives in ~/Documents/stride-data (override with STRIDE_DATA). Nothing is deleted on the phone.
set -e
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DATA=${STRIDE_DATA:-$HOME/Documents/stride-data}
RAW=$DATA/lab/raw
R25=$DATA/lab/r25
TRAIN=0; INSTALL=0
for a in "$@"; do
  case $a in --train) TRAIN=1 ;; --install) INSTALL=1 ;; *) echo "unknown option $a"; exit 2 ;; esac
done

"$ADB" get-state >/dev/null 2>&1 || { echo "Phone not found: plug it in and allow USB debugging."; exit 1; }
mkdir -p "$RAW"
new=0
for f in $("$ADB" shell 'ls "/sdcard/Download/Stride lab/" 2>/dev/null' | tr -d '\r'); do
  [ -f "$RAW/$f" ] && [ "$f" != bouts.txt ] && continue      # bouts.txt grows: always fetch it
  "$ADB" pull "/sdcard/Download/Stride lab/$f" "$RAW/" >/dev/null && new=$((new + 1))
done
n=$(ls "$RAW"/*.csv 2>/dev/null | wc -l)
[ -f "$RAW/bouts.txt" ] && echo "Walk log: $(grep -vc '^#' "$RAW/bouts.txt") walks in $RAW/bouts.txt"
echo "Recordings: $n on the laptop ($new new) in $RAW"
[ "$n" -gt 0 ] || { echo "Nothing recorded yet: Settings > Calibrate & record a walk."; exit 0; }

echo; echo "== Each recording through the app's code (signed error; 'stored' = what Stride saves)"
MAIN=LabReplay "$ROOT/tools/real/run.sh" "$RAW"/*.csv | tee "$DATA/lab/report.txt"

rm -rf "$R25"
python3 -I "$ROOT/tools/real/lab2csv.py" "$R25" "$RAW"/*.csv >/dev/null

if [ $TRAIN = 1 ]; then
  PY=$HOME/.venvs/stride-ml/bin/python
  [ -x "$PY" ] || { echo "Training needs the venv: see tools/real/README.md"; exit 1; }
  [ -f "$DATA/r25/index.csv" ] || { echo "Training also needs the Cambridge walks in $DATA/r25 (tools/real/README.md)"; exit 1; }
  echo; echo "== Step model on your walks (about 30-45 minutes)"
  "$PY" -I "$ROOT/tools/ml/train.py" "$DATA/r25" personal "$R25" "$DATA/lab/stepnet_personal.txt"
  if [ -f "$DATA/lab/stepnet_personal.txt" ] && [ "$DATA/lab/stepnet_personal.txt" -nt "$DATA/lab/report.txt" ]; then
    cp "$DATA/lab/stepnet_personal.txt" "$ROOT/tools/ml/stepnet.txt"
    python3 -I "$ROOT/tools/ml/export_java.py" "$ROOT/tools/ml/stepnet.txt" "$ROOT/app/src/main/java/com/arco/stride/StepNetWeights.java"
  fi
fi

if [ $INSTALL = 1 ]; then
  echo; echo "== Build and install"
  (cd "$ROOT" && python3 build_apk.py >/dev/null) && "$ADB" install -r "$HOME/Downloads/utilities/Stride.apk"
fi
