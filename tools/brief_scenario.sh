#!/bin/sh
# 미팅 전 브리핑 시험: 한빛병원(거래처) 남쪽 약 5km 에서 출발 → 북쪽으로 주행 → 1km 안에 들어오면 지난 상담·할 일 브리핑.
# 사용: tools/brief_scenario.sh [--fresh]   (vc_heartbeat.sh 실행 중이어야 함, 위치는 adb emu geo fix 로 움직인다)
set -e
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:8088/vehicle
put() { curl -s -X PUT "$V/$1" -o /dev/null; }
geo() { $ADB emu geo fix "$2" "$1" >/dev/null; }   # geo 위도 경도
say() { echo "[$(date +%H:%M:%S)] $1"; }
LAT=37.4959; LON=127.0293                          # 한빛병원(가상) 위치
put "gear?gear=Park"
if [ "$1" = "--fresh" ]; then
  $ADB uninstall com.roadoffice.app >/dev/null 2>&1 || true
  $ADB install -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
  geo $LAT $LON; sleep 2
  $ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null; sleep 12
  $ADB shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver --ez month true >/dev/null; sleep 4
fi
say "남쪽 5km 에서 출발"
geo 37.4500 $LON; sleep 3
ODO=$(curl -s $V/state | python3 -I -c "import json,sys;print(int(json.load(sys.stdin)['odometer']['traveled_distance_km'])+1)")
put "odometer?traveled_distance_km=$ODO"
put "gear?gear=Drive"; put "controls?speed_kph=50"; sleep 2
for lat in 37.4560 37.4620 37.4680 37.4740 37.4800 37.4860 37.4880 37.4900; do
  geo $lat $LON; ODO=$((ODO + 1)); put "odometer?traveled_distance_km=$ODO"; sleep 2
done
say "1km 안 — 브리핑 대기"; sleep 8
