#!/usr/bin/env bash
# Quick config helpers for DoorCam. Usage:
#   ./scripts/cfg.sh rot 90          # rotate view 0/90/180/270
#   ./scripts/cfg.sh zoom 2.5        # digital zoom 1.0–10.0
#   ./scripts/cfg.sh crop 0.5 0.5    # crop center (cx, cy) as fraction of sensor
#   ./scripts/cfg.sh flipv on|off    # mirror preview vertically (top↔bottom)
#   ./scripts/cfg.sh fliph on|off    # mirror preview horizontally (left↔right)
#   ./scripts/cfg.sh thresh 18       # motion detection threshold (1..100, lower = more sensitive)
#   ./scripts/cfg.sh cam 2           # switch camera id
#   ./scripts/cfg.sh shot            # grab a screenshot to /tmp/doorcam_now.png
#   ./scripts/cfg.sh reset           # clear all settings
set -eu
D="${DEVICE:-172.20.10.8:5555}"
PKG=com.z.doorcam
ACT=$PKG/.ViewerActivity

on_off() { [ "$1" = "on" -o "$1" = "true" -o "$1" = "1" ] && echo true || echo false; }

case "${1:-}" in
  rot)   adb -s "$D" shell am start -n "$ACT" --ei rot "$2" ;;
  zoom)  adb -s "$D" shell am start -n "$ACT" --ef zoom "$2" ;;
  crop)  adb -s "$D" shell am start -n "$ACT" --ef cx "$2" --ef cy "$3" ;;
  flipv) adb -s "$D" shell am start -n "$ACT" --ez flipv "$(on_off "$2")" ;;
  fliph) adb -s "$D" shell am start -n "$ACT" --ez fliph "$(on_off "$2")" ;;
  thresh) adb -s "$D" shell am start -n "$ACT" --ei thresh "$2" ;;
  cam)   adb -s "$D" shell am start -n "$ACT" --es camera_id "$2" ;;
  shot)  adb -s "$D" exec-out screencap -p > /tmp/doorcam_now.png && echo /tmp/doorcam_now.png ;;
  reset) adb -s "$D" shell am force-stop $PKG
         adb -s "$D" shell pm clear $PKG
         adb -s "$D" shell am start -n "$ACT" ;;
  *)     echo "usage: $0 {rot <0|90|180|270>|zoom <1.0-10.0>|crop <cx> <cy>|flipv on|off|fliph on|off|thresh <N>|cam <id>|shot|reset}" >&2; exit 1 ;;
esac