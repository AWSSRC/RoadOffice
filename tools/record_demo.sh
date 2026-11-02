#!/bin/sh
# 시연 영상 초안 녹화 — 영업사원의 하루를 에뮬레이터에서 자동으로 재현하며 화면 녹화.
# 준비: 에뮬레이터 실행, adb forward tcp:8088 tcp:8088, tools/vc_heartbeat.sh 실행 중, 디버그 APK 빌드
# 결과: ../demo/roadoffice_demo_raw.mp4  (소리 없음 — 음성 안내는 자막·내레이션으로 입힘)
set -e
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:8088/vehicle
OUT=../demo; mkdir -p $OUT
put() { curl -s -X PUT "$V/$1" -o /dev/null; }
tap() { tools/ui.sh tap "$1" >/dev/null; }
say() { echo "[$(date +%H:%M:%S)] $1"; }

say "깨끗한 설치"
$ADB uninstall com.roadoffice.app >/dev/null 2>&1 || true
$ADB install -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
put "gear?gear=Park"
$ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null
sleep 12
$ADB shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver >/dev/null
sleep 2
ODO=$(curl -s $V/state | python3 -I -c "import json,sys;print(int(json.load(sys.stdin)['odometer']['traveled_distance_km'])+1)")
put "odometer?traveled_distance_km=$ODO"

say "녹화 시작"
$ADB shell screenrecord --time-limit 170 --bit-rate 8000000 /sdcard/roadoffice_demo.mp4 &
REC=$!
sleep 2

say "1. 출근 전 — 오늘 운행 비어 있음, 거래처 목록"
tap "거래처"; sleep 3; tap "오늘 운행"; sleep 3

say "2. 출발 → 주행 중 워크마일 화면"
put "gear?gear=Drive"; put "controls?speed_kph=60"
i=1; while [ $i -le 18 ]; do put "odometer?traveled_distance_km=$((ODO + i))"; sleep 0.6; i=$((i + 1)); done

say "3. 한빛병원 도착·주차 → 30초 뒤 운행 저장(업무)"
put "controls?speed_kph=0"; sleep 2; put "gear?gear=Park"
sleep 4; tap "거래처"; sleep 8; tap "오늘 운행"; sleep 22

say "4. 미팅 후 출발 → '한빛병원 미팅 어땠어요?'"
put "gear?gear=Drive"; put "controls?speed_kph=30"; sleep 12
put "controls?speed_kph=0"; sleep 2; put "gear?gear=Park"; sleep 2

say "5. 말한 내용 → 상담 일지(요약·할 일·후속 일정)"
$ADB shell "am broadcast -a ADD_MEETING_NOTE -n com.roadoffice.app/.gleo.GleoReceiver --es ACTION_PARAM '{\"note\":\"원장님이 신제품 샘플 반응이 좋다고 하셨어요 다음 주 화요일에 다시 연락 주신대요 그리고 금요일까지 단가표 보내 드리기로 했어요\",\"clientName\":\"한빛병원\"}'" >/dev/null
sleep 12; tap "상담 일지"; sleep 8

say "6. 월말 — 워크마일 정산 내보내기"
tap "워크마일"; sleep 3; tap "정산 파일 내보내기"; sleep 7

say "녹화 종료"
$ADB shell pkill -INT screenrecord || true
wait $REC 2>/dev/null || true
sleep 2
$ADB pull /sdcard/roadoffice_demo.mp4 $OUT/roadoffice_demo_raw.mp4 >/dev/null
ls -la $OUT/roadoffice_demo_raw.mp4
