#!/bin/sh
# 차 안 AI 장면만 따로 녹화 — AI 생성은 녹화 밖에서 먼저 끝내고, 결과 화면만 짧게 찍는다 (Mac 메모리가 빠듯하면 생성에 수 분)
#  ai_notes: 상담 일지의 'AI 다음 행동 제안'(후속 문자 초안 · 다음 방문 한 줄) / ai_today: 오늘 화면 '다가오는 후속 일정 · AI' / ai_ask: Gleo 질문 → AI 답
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
OUT=../demo/v2
say() { echo "[$(date +%H:%M:%S)] $1"; }
tap() { tools/ui.sh tap "$1" >/dev/null; }
front() { for k in 1 2 3; do $ADB shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q com.roadoffice.app && return; $ADB shell am force-stop ai.umos.vehiclecontrol >/dev/null 2>&1; $ADB shell am start -n com.roadoffice.app/.MainActivity >/dev/null; sleep 2; done; }
rec() { front; $ADB shell rm -f /sdcard/seg.mp4; $ADB shell screenrecord --bit-rate 8000000 --time-limit 60 /sdcard/seg.mp4 & sleep 1; }
stop() { sleep 1; $ADB shell pkill -INT screenrecord || true; sleep 2; $ADB pull /sdcard/seg.mp4 "$OUT/seg_$1.mp4" >/dev/null; say "   → seg_$1.mp4"; }
gleo() { $ADB shell "am broadcast -a $1 -n com.roadoffice.app/.gleo.GleoReceiver --es ACTION_PARAM '$2'" >/dev/null; }

front
say "AI 후속 제안 미리 만들기 (녹화 밖)"
$ADB logcat -c; $ADB shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver --ez ai true >/dev/null
for k in $(seq 1 200); do sleep 3; $ADB logcat -d | grep -q "시연 AI 후속 제안 준비 끝" && break; done
$ADB logcat -d | grep "RoadOffice: AI 후속" | cut -c50-

say "ai_notes"; tap "상담 일지"; sleep 2
rec; sleep 9; stop ai_notes
say "ai_today"; tap "오늘 운행"; sleep 2
rec; sleep 8; stop ai_today

say "AI에게 묻기 — 답은 녹화 밖에서 기다린다"
$ADB logcat -c; gleo ASK_ROADOFFICE '{"question":"이번 달 한빛병원 몇 번 갔어?"}'
for k in $(seq 1 120); do sleep 3; $ADB logcat -d | grep -q "RoadOffice: AI 답" && break; done
$ADB logcat -d | grep "RoadOffice: AI 답" | cut -c50-
front; tap "오늘 운행"; sleep 1
rec; sleep 9; stop ai_ask
say "완료"
