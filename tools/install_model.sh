#!/bin/sh
# 차 안 AI 모델(Qwen2.5-0.5B-Instruct, Apache-2.0, MediaPipe .task 546MB)을 받아 에뮬레이터/기기에 넣는다.
# 모델은 APK 밖 파일이다. 없으면 앱은 규칙 정리기·문장 틀로만 동작한다.
set -e
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
URL=https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task
SHA=e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2
F=${1:-${TMPDIR:-/tmp}/roadoffice_llm.task}
[ -f "$F" ] || curl -L --fail -o "$F" "$URL"
echo "$SHA  $F" | shasum -a 256 -c -
$ADB shell mkdir -p /data/local/tmp/llm
$ADB push -Z "$F" /data/local/tmp/llm/roadoffice_llm.task   # -Z: 압축 전송은 큰 파일에서 멈춤
echo "완료 — 앱을 다시 시작하면 차 안 AI 가 켜집니다."
