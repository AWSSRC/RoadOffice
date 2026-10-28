#!/bin/sh
# 에뮬레이터 UI 조작 도우미: tools/ui.sh tap "<화면 글자>"  |  tools/ui.sh texts
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
XML=$($ADB shell cat /sdcard/ui.xml)
if [ "$1" = "texts" ]; then echo "$XML" | python3 -I -c "import sys,re;print([m.group(1) for m in re.finditer(r'text=\"([^\"]+)\"',sys.stdin.read())])"; exit; fi
XY=$(echo "$XML" | python3 -I -c "
import sys,re;t=sys.stdin.read();k=sys.argv[1]
for m in re.finditer(r'<node [^>]*>',t):
  n=m.group(0)
  if 'text=\"'+k+'\"' in n:
    b=list(map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups()));print((b[0]+b[2])//2,(b[1]+b[3])//2);break" "$2")
[ -n "$XY" ] && $ADB shell input tap $XY && echo "tap $2 ($XY)" || echo "not found: $2"
