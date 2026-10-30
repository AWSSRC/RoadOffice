#!/bin/sh
# 시연 영상 v2 실제 앱 장면 녹화 (장면별 클립) — 심사위원 제안 순서:
#  01 출발·주행·1km 브리핑 → 02 도착·주차·업무 저장 → 03 미팅(하차) 중 충전 → 착석 시 회고 질문
#  → 04 회고 답(Gleo 메모) → 상담 일지 → 05 동승자 탑승 보호 → 06 충전 금액 입력 → 07 Gleo 업무 지정
#  → 08 하루 마감 → 09 월 정산 내보내기.  결과: ../demo/v2/seg_NN.mp4, 정산 PDF ../demo/v2/report.pdf
# 디버그 APK·vc_heartbeat.sh 필요. 위치는 adb emu geo fix.
set -e
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:8088/vehicle
OUT=../demo/v2; mkdir -p $OUT
put() { curl -s -X PUT "$V/$1" -o /dev/null; }
post() { curl -s -X POST "$V/$1" -o /dev/null; }
seat() { post "seat?kind=occupied&area=$1&value=$2"; }
soc() { post "ev-battery?field=soc&value=$1"; }
geo() { $ADB emu geo fix "$2" "$1" >/dev/null; }
tap() { tools/ui.sh tap "$1" >/dev/null; }
say() { echo "[$(date +%H:%M:%S)] $1"; }
odo() { curl -s $V/state | python3 -I -c "import json,sys;print(int(json.load(sys.stdin)['odometer']['traveled_distance_km']))"; }
rec() { $ADB shell rm -f /sdcard/seg.mp4; $ADB shell screenrecord --bit-rate 8000000 --time-limit 120 /sdcard/seg.mp4 & sleep 1; }
stop() { sleep 1; $ADB shell pkill -INT screenrecord || true; sleep 2; $ADB pull /sdcard/seg.mp4 "$OUT/seg_$1.mp4" >/dev/null; say "   → seg_$1.mp4"; }
gleo() { $ADB shell "am broadcast -a $1 -n com.roadoffice.app/.gleo.GleoReceiver --es ACTION_PARAM '$2'" >/dev/null; }
LAT=37.4959; LON=127.0293   # 한빛병원(가상)

say "준비: 깨끗한 설치 + 이번 달 기록 + 실비 정산"
seat passenger false; seat rear_left false; seat rear_right false; seat driver true; soc 55; put "gear?gear=Park"
$ADB uninstall com.roadoffice.app >/dev/null 2>&1 || true
$ADB install -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
geo $LAT $LON; sleep 2
$ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null; sleep 12
$ADB shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver --ez month true >/dev/null; sleep 4
tap "워크마일"; sleep 1; tap "실비"; sleep 1; tap "오늘 운행"; sleep 1
geo 37.4500 $LON; sleep 2
O=$(( $(odo) + 1 )); put "odometer?traveled_distance_km=$O"; sleep 2

say "01 출발 → 주행 → 1km 브리핑"
rec
sleep 2; put "gear?gear=Drive"; put "controls?speed_kph=50"; sleep 2
for lat in 37.4560 37.4620 37.4680 37.4740 37.4800 37.4860 37.4880 37.4900; do geo $lat $LON; O=$((O + 1)); put "odometer?traveled_distance_km=$O"; sleep 2; done
sleep 9
stop 01

say "02 도착 → 주차 → 30초 뒤 업무 저장"
rec
geo 37.4955 $LON; O=$((O + 1)); put "odometer?traveled_distance_km=$O"; sleep 1
put "controls?speed_kph=0"; sleep 2; put "gear?gear=Park"; sleep 33
stop 02

say "03 미팅(하차) 중 충전 → 다시 탐 → 회고 질문"
rec
seat driver false; sleep 3
for v in 61 68 74 78; do soc $v; sleep 2; done
sleep 5; seat driver true; sleep 9
stop 03

say "04 회고 답 → 상담 일지 (에뮬레이터 마이크 미지원 — 같은 문장을 Gleo 메모로 전달)"
rec
gleo ADD_MEETING_NOTE '{"note":"원장님이 신제품 샘플 반응이 좋다고 하셨어요 다음 주 화요일에 다시 연락 주신대요 그리고 금요일까지 단가표 보내 드리기로 했어요","clientName":"한빛병원"}'
sleep 5; tap "상담 일지"; sleep 6
stop 04

say "05 동승자 탑승 → 보호"
rec
sleep 2; seat passenger true; sleep 4; tap "오늘 운행"; sleep 4
seat passenger false; sleep 3
stop 05

say "06 충전 금액 입력"
tap "오늘 운행"; sleep 1
rec
sleep 2; tap "금액(원)"; sleep 1; $ADB shell input text 18500; sleep 2; $ADB shell input keyevent 111 || true; sleep 2; tap "저장"; sleep 5
stop 06

say "07 거래처 아닌 곳 운행 → Gleo '이번 운행 업무로 해 줘'"
geo 37.5100 127.0450; sleep 1
put "gear?gear=Drive"; put "controls?speed_kph=40"; for i in 1 2 3 4 5 6; do O=$((O + 1)); put "odometer?traveled_distance_km=$O"; sleep 0.5; done
put "controls?speed_kph=0"; sleep 2; put "gear?gear=Park"; sleep 34
tap "오늘 운행"; sleep 1
rec
sleep 3; gleo MARK_TRIP_PURPOSE '{"purpose":"business"}'; sleep 6
stop 07

say "08 하루 마감 30초"
rec
sleep 1; tap "오늘 마감"; sleep 6
# 미분류 운행을 '개인'으로 — 화면이 바뀔 때까지 다시 누른다
for k in 1 2 3; do tap "개인"; sleep 3; tools/ui.sh texts | grep -q "미분류 운행 —" || break; done
sleep 2
L=$(tools/ui.sh texts | tr ',' '\n' | grep -o "이대로 마감[^']*\|마감 완료" | head -1)
[ -n "$L" ] && tap "$L"; sleep 4
stop 08

say "09 월 정산 내보내기"
rec
tap "워크마일"; sleep 2; tap "km 단가"; sleep 3   # 월 정산은 km 단가로 — 업무 km × 회사 단가
for k in 1 2 3; do tools/ui.sh tap "이번 달 정산 내보내기" | grep -q "^tap" && break; $ADB shell input swipe 1600 1100 1600 500 300; sleep 2; done
sleep 6
stop 09
F=$($ADB shell run-as com.roadoffice.app --user 10 ls files/reports/ | grep pdf | head -1 | tr -d '\r')
[ -n "$F" ] && $ADB exec-out run-as com.roadoffice.app --user 10 cat "files/reports/$F" > $OUT/report.pdf
say "완료: $OUT"
