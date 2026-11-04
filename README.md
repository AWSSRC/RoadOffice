# 로드오피스 (RoadOffice)

> **차 안에서 일하는 영업직을 위한 Pleos Connect 업무 앱** — 시동부터 주차까지 차량 신호로 운행을 기록하고, 거래처 앞에서 지난 상담을 브리핑하고, 미팅 후 차에 타면 말로 상담 일지를 남기고, 월말 정산을 한 번에 만든다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작 · 팀 AWSSRC (개인)

## APK

- `apk/AWSSRC_RoadOffice_release.apk` — release 빌드 (APK Signature Scheme v3 서명, arm64-v8a · x86_64)

## 테스트 환경

| 항목 | 값 |
|---|---|
| 플랫폼 | Pleos Connect (Android Automotive OS), Pleos Connect Emulator 2.1.0 · API 34 · arm64 |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · SpeechToText/TextToSpeech 3.3.1 (ON_DEVICE) · LLM 2.1.3.2 · Gleo Interface |
| 차 안 AI 모델 | Qwen2.5-0.5B-Instruct q8 (.task 546MB, Apache-2.0) · MediaPipe LLM Inference 0.10.24 — APK 밖 파일, `adb push -Z <모델> /data/local/tmp/llm/roadoffice_llm.task` (없으면 규칙 정리기로 동작) |
| 빌드 | JDK 17 · Gradle 8.7 · AGP 8.5.2 · Kotlin 1.9.24 · Jetpack Compose |
| 미검증 | 실차, 실제 마이크 입력, Pleos 플랫폼 LLM 응답(에뮬레이터는 플랫폼 LLM 꺼짐 → 차 안 소형 모델이 요약, 실패 시 규칙 정리기), 내비 경로 서버 이벤트 |

## 실행 방법

1. Android Studio SDK Manager 에 Pleos Connect Emulator 이미지를 추가하고 AVD(API 34, RAM 4GB) 를 만든 뒤 Cold boot.
2. 설치: `adb install -g apk/AWSSRC_RoadOffice_release.apk` → 앱 실행 → 권한 허용.
3. 차량 신호는 Pleos Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088`) 에서 기어·속도·주행거리·좌석 점유·배터리를 바꾼다.
   - Virtual Console 은 브라우저 세션이 열려 있어야 쓰기가 된다 — 브라우저를 열어 두거나 `tools/vc_heartbeat.sh` 실행.
4. 시연 시나리오 (debug 빌드, 시연용 가상 데이터 포함):
   - `./gradlew assembleDebug` 후 `tools/cabin_scenario.sh` — 출발 → 거래처 도착·업무 분류 → 미팅 중 충전 포착 → 다시 타면 회고 질문 → 동승자 보호 → 하루 마감
   - `tools/brief_scenario.sh` — 거래처 1km 진입 브리핑 (위치는 `adb emu geo fix`)
   - `tools/record_demo_v2.sh` — 시연 영상 장면 9개 녹화

## 주요 기능

| 기능 | 쓰는 차량 신호 |
|---|---|
| 워크마일 — 운행 자동 기록·업무/개인 분류·운행기록부·유류비 청구서·정산 PDF | 기어 · 주행거리계 · 위치 |
| 거래처 1km 앞 브리핑 (주행 중 한 줄 + 음성) | 위치 · 내비 |
| 출발 전 회고 — P단 운전석 착석 시 "미팅 어땠어요?" → 요약·할 일·후속 일정 | 좌석 점유 · 기어 · STT/TTS · LLM |
| 동승자 보호 — 옆·뒷자리 착석 시 거래처·상담 내용 음성·화면 차단 | 좌석 점유 |
| 주유·충전 자동 포착 (km 단가 / 실비 정산) | 배터리 SOC · 연료 |
| 하루 마감 30초 | 기어 · 좌석 |
| 상담 활동 CSV (SFA·CRM 가져오기용, 상담 원문 제외) · 고유명사 사전 교정 · 정차 중 확인 | — |
| Gleo 음성 명령 함수 3개 (운행 목적 지정 · 상담 메모 · 거래처 브리핑) | Gleo Interface |

## 빌드·테스트

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew testDebugUnitTest      # 단위 테스트
./gradlew assembleDebug          # 시연용 (가상 데이터 주입 receiver 포함)
./gradlew assembleRelease        # 서명 키(keystore.properties)가 있어야 함 — 저장소에는 없음
```

## 보안·개인정보

- 차량 제어 권한 없음(조회만) · 앱 코드에 네트워크 호출 없음 · 상담 내용·위치·거래처는 차 밖으로 나가지 않음
- Room + SQLCipher(DB 전체 암호화), 키는 Android Keystore AES-256-GCM, `allowBackup=false`
- 점검(2026-11-02): ASH 10개 스캐너 발견 0건 · OSV 의존성 취약점 0건(Pleos LLM SDK 경유 okhttp·okio 는 수정 버전으로 고정)
- 오픈소스 라이선스: 앱 신호 탭 → "오픈소스 라이선스" (`app/src/main/assets/licenses/NOTICE.txt`)

## 폴더

```
app/src/main/java/com/roadoffice/app/
  vehicle/   차량 신호 (Pleos SDK · AAOS)        workmile/  운행 기록·분류·정산·주유충전·하루 마감
  cabin/     좌석 점유·동승자·좌석 값 자가 보정   meeting/   브리핑·회고·상담 정리(Pleos LLM→차 안 모델→규칙)·사전 교정
  gleo/      Gleo Interface receiver             data/      Room+SQLCipher·Keystore
  ui/        Compose 화면 (주행/정차)
app/src/debug/   시연용 가상 데이터 (release 에 포함되지 않음)
tools/           에뮬레이터 시연·녹화 스크립트
apk/             제출용 release APK
```
