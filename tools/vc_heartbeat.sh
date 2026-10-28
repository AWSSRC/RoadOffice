#!/bin/sh
# Pleos Virtual Console 은 브라우저 세션 하트비트(3초 타임아웃)가 있어야 차량 신호 제어가 활성화된다.
# 자동 시험(drive_scenario.sh) 중 브라우저 없이 세션을 유지하기 위한 하트비트. 로컬(127.0.0.1)만 사용.
while true; do
  curl -s -X POST "http://127.0.0.1:8088/browser/session?client=roadoffice-cli" -o /dev/null || true
  sleep 1
done
