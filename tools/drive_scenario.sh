#!/bin/sh
# 워크마일 시험 시나리오: 출발(D) → 주행(속도·주행거리 증가) → 주차(P) → 주차 확정(30초) 대기.
# 사용: tools/drive_scenario.sh [시작km] [이동km]   (vc_heartbeat.sh 실행 중이어야 함)
V=http://127.0.0.1:8088/vehicle
START=${1:-1000}; DIST=${2:-14}
put() { curl -s -X PUT "$V/$1" -o /dev/null -w "$1 → %{http_code}\n"; }
put "odometer?traveled_distance_km=$START"; sleep 2
put "gear?gear=Drive"; sleep 1
put "controls?speed_kph=50"
i=1; while [ $i -le $DIST ]; do put "odometer?traveled_distance_km=$((START + i))" >/dev/null; sleep 0.5; i=$((i + 1)); done
echo "odometer → $((START + DIST)) km"
put "controls?speed_kph=0"; sleep 2
put "gear?gear=Park"
echo "주차 확정 대기 32초..."; sleep 32
