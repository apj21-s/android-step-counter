#!/bin/sh
# Stride's own battery use, read-only (changes nothing on the phone). Run it yourself, phone on USB:
#   tools/phone/battery.sh          since the phone was last unplugged from the charger
# Look at: the "Uid u0a..." power line for Stride, its wake lock time, and its sensor time.
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
UID_=$("$ADB" shell dumpsys package com.arco.stride | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
[ -n "$UID_" ] || { echo "Stride not found (phone connected? USB debugging on?)"; exit 1; }
APPID=$((UID_ - 10000))
echo "Stride uid $UID_ (u0a$APPID). Since last unplugged:"
"$ADB" shell dumpsys batterystats --charged com.arco.stride | grep -E "Estimated power use|Uid u0a$APPID|Wake lock|Sensor|Job|Foreground service|Total cpu|Cpu times" | head -40
