#!/bin/sh
# 좌석·배터리 시험 시나리오 (디버그 빌드, vc_heartbeat.sh 실행 중이어야 함):
#  운전자 탑승 → 한빛병원 도착·주차(업무 저장) → 내려서 미팅(그동안 충전 55→78%) → 다시 탐 → 회고 질문
#  → 동승자 탑승(보호) → 하루 마감.  로그: adb logcat -s RoadOffice
#  실차처럼 미팅 동안 앱(헤드유닛)이 꺼졌다 켜지는 경우도 시험한다: --restart
set -e
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:8088/vehicle
put() { curl -s -X PUT "$V/$1" -o /dev/null; }
post() { curl -s -X POST "$V/$1" -o /dev/null; }
seat() { post "seat?kind=occupied&area=$1&value=$2"; }
soc() { post "ev-battery?field=soc&value=$1"; }
say() { echo "[$(date +%H:%M:%S)] $1"; }
SHOT=${SHOT:-../assets/screens}
cap() { $ADB exec-out screencap -p > "$SHOT/$1.png"; echo "   화면: $SHOT/$1.png"; }
tap() { tools/ui.sh tap "$1" >/dev/null; }

seat passenger false; seat rear_left false; seat rear_right false
seat driver true; soc 55; put "gear?gear=Park"
RESTART=0; [ "$1" = "--restart" ] && RESTART=1
if [ "$1" != "--keep" ]; then
  $ADB uninstall com.roadoffice.app >/dev/null 2>&1 || true
  $ADB install -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
fi
$ADB logcat -c
$ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null; sleep 12
$ADB shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver --ez month true >/dev/null; sleep 3
ODO=$(curl -s $V/state | python3 -I -c "import json,sys;print(int(json.load(sys.stdin)['odometer']['traveled_distance_km'])+1)")
put "odometer?traveled_distance_km=$ODO"

say "1. 출발 → 한빛병원 도착·주차"
put "gear?gear=Drive"; put "controls?speed_kph=60"
i=1; while [ $i -le 12 ]; do put "odometer?traveled_distance_km=$((ODO + i))"; sleep 0.5; i=$((i + 1)); done
put "controls?speed_kph=0"; sleep 2; put "gear?gear=Park"
say "   주차 확정 대기 33초"; sleep 33

say "2. 내려서 미팅 — 그동안 충전 55% → 78%"
seat driver false; sleep 2
if [ "$RESTART" = 1 ]; then
  say "   (실차처럼 헤드유닛 꺼짐 — 앱 종료한 채 충전)"
  $ADB shell am force-stop com.roadoffice.app
fi
for v in 60 66 72 78; do soc $v; sleep 2; done
sleep 6

say "3. 미팅 끝, 다시 탐 → 회고 질문(출발 전)"
seat driver true
if [ "$RESTART" = 1 ]; then sleep 2; $ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null; sleep 4; fi
sleep 6; cap 09_debrief_seated
sleep 4

say "4. 동승자 탑승 → 보호"
seat passenger true; sleep 4
tap "워크마일"; sleep 2; tap "실비"; sleep 1   # 실비 정산이면 충전 배너에서 금액을 묻는다
tap "오늘 운행"; sleep 2; cap 10_refill_debrief_guest
tap "상담 일지"; sleep 2; cap 11_privacy_notes
seat passenger false; sleep 3
say "5. 하루 마감"
tap "오늘 운행"; sleep 1; tap "오늘 마감"; sleep 4; cap 12_day_close
tap "워크마일"; sleep 1   # (마감 패널이 열려 있으면 탭이 없어 무시됨)
