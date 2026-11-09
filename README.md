# Voyadio(보야디오) — 차 안에서 탐험하는 세계 라디오

운전대 버튼 한 번이면 지구 반대편의 라디오가 흐르고, 국경을 넘으면 그 나라의 소리로 바뀝니다.
달리는 도로 위에서 귀로 세계를 여행하는 Pleos 차량용 라디오입니다(208개 나라·지역, 방송 9,170개).

### 시연 영상
**60초 — 하루의 드라이브(실사 장면 + 앱 화면)**

https://github.com/user-attachments/assets/abb2223e-4881-4241-ab7c-ce40d8237352


**30초 — Pleos 가상 차량 실제 화면**

https://github.com/user-attachments/assets/3cfd5335-41a4-4600-8240-318133125b2b


- 언어: Kotlin · Android Automotive OS 전용 앱(AndroidX Media3)
- Pleos Connect SDK: Vehicle · TextToSpeech · SpeechToText · NaviHelper · FusedLocation + Gleo 앱 스키마
- 휴대폰 앱과 같은 서버·방송 목록·계정을 씁니다(차 앱 단독으로도 로그인 없이 바로 사용)

---

## 1. 바로 설치해 보기 (APK)

| 항목 | 내용 |
|---|---|
| 파일 | [`apk/디케이스_Voyadio_release.apk`](apk/) — Pleos 빌드 · 출시 서명 release |
| 패키지 · 버전 | `com.voyadio.automotive` · 0.1.0 |
| 권장 환경 | **Pleos Connect 가상 차량 v2.1.0**(Android API 34) + Virtual Console |
| 그 밖의 환경 | 일반 Android Automotive 에뮬레이터(API 29+)에서도 실행됨 — Pleos 기능(기어·차 목소리·내비·위치)만 조용히 꺼짐 |
| 필요 조건 | 인터넷 연결(라디오 소리는 각 방송국에서 직접 받음) · 로그인 불필요 |

```bash
# 가상 차량을 켠 상태에서
adb install -r "apk/디케이스_Voyadio_release.apk"
```

> 휴대폰이 함께 연결돼 있으면 `adb -s emulator-5554 install …`처럼 기기를 지정하세요.
> 앱 목록에 'Voyadio'가 두 개 보일 수 있습니다. 하나는 앱 화면(지구본), 하나는 차량 기본 미디어 화면용 재생 서비스로, 같은 앱입니다.

## 2. 체험 순서 (가상 차량, 약 5분)

공모전 문서의 '하루의 드라이브' 장면 번호와 맞췄습니다.

| # | 해 보기 | 보이는 것 · 들리는 것 | 쓰는 Pleos 기능 |
|---|---|---|---|
| ① | 앱 목록에서 Voyadio 실행 | 몇 초 뒤 **스스로 방송 시작**. 지구본 위에 방송 나라·수도, 떨어진 거리 | — |
| ② | '다음 방송'(또는 미디어 화면·운전대 '다음') | 다른 나라 방송으로 바뀌고 **차의 목소리**로 "○○. △△에서 오는 방송. N km 떨어진 곳" | TextToSpeech |
| ③ | Virtual Console에서 **기어 D** | 앱 화면이 잠기고 차량 기본 미디어 화면·운전대·목소리로만 조작. **기어 P**로 돌아오면 화면이 다시 열림 | Vehicle |
| ④ | 정차 중 '말하기' 버튼 → "미국 채널 찾아줘", "이 채널 저장해줘" | 해당 나라 방송으로 바꾸고 "저장했어요" 대답 | SpeechToText · Gleo |
| ⑤ | '항로' → 언어권·내용(예: 클래식) | 그 항로 안에서만 '다음 방송'이 이어짐 | — |
| ⑥-1 | 기어 D로 방송을 몇 개 들은 뒤 **기어 P** | **'오늘의 항해'**: 차의 목소리로 "오늘 달리는 동안 N개 나라의 방송을 만났어요. 가장 먼 곳은 …", 지구본에 만난 나라 표시 | Vehicle · TextToSpeech |
| ⑥-2 | 정차 중 '캠핑' 버튼 | 캠핑 화면: 지금 시간에 어울리는 분위기 고르기, 화면 밝기 15%·화면 끄기, 배터리가 정한 값 아래로 내려가면 라디오 자동 꺼짐 | Vehicle |
| ⑧-1 | Virtual Console에서 **내비 목적지 설정** | 항로가 '가는 길 · (목적지 나라)'로 바뀌고 지구본에 목적지 깃발 | NaviHelper |
| ⑧-2 | 에뮬레이터 **위치를 다른 나라로 이동**(Extended controls → Location) | 약 20초 간격으로 두 번 연속 같은 새 나라로 확인되면 "○○에 들어왔어요" + 그 나라 방송 | FusedLocation |

장면 ⑦(다음 날 휴대폰으로 이어 듣기)은 휴대폰 앱과 계정을 연결해야 합니다. 앱 화면의 '휴대폰과 연결' 버튼을 누르면 나오는 QR로 연결합니다.

### 가상 차량에서 확인할 수 없는 것 (실차 확인 예정)
- **실제 목소리 명령**: 가상 차량은 노트북 마이크 소리를 차 음성 인식에 넘기지 않습니다. 그래서 '말하기'는 녹음 파일로 확인했습니다.
  디버그 빌드(`assemblePleosDebug`)의 `SttProbe`가 녹음 파일을 차 음성 인식에 넣어 줍니다. 사용법은 [`app/src/pleosDebug/…/SttProbe.kt`](app/src/pleosDebug/java/com/voyadio/automotive/pleos/SttProbe.kt) 머리 주석에 있습니다.
- **내비 도착 알림**과 **유럽 국경 실제 주행**: 가상 차량의 지도는 한국용입니다. 그래서 국경은 위치 이동으로 재현했습니다.

## 3. 소스에서 빌드 · 시험

필요: **JDK 17**, **Android SDK 36**(`ANDROID_HOME` 또는 `local.properties`의 `sdk.dir`). Gradle은 동봉된 `./gradlew`(8.14)를 씁니다.
Pleos SDK는 공개 저장소 `nexus-playground.pleos.ai`에서 자동으로 내려받습니다(인증 불필요).

```bash
./gradlew :app:assemblePleosDebug          # Pleos 빌드(개발용, 패키지 com.voyadio.automotive.dev)
./gradlew :app:testPleosDebugUnitTest      # 단위 시험 255개
./gradlew :app:testGenericDebugUnitTest    # Pleos SDK 없는 빌드의 단위 시험 244개
```

- 빌드는 두 갈래입니다. **`pleos`**는 Pleos Connect SDK를 포함하고(현대·기아·제네시스), **`generic`**은 SDK 없이 다른 차량 앱 마켓용입니다.
  SDK 라이선스상 SDK는 Pleos Connect용 앱에만 넣습니다.
- 출시 서명 키는 저장소에 포함하지 않습니다. 소스에서 만든 release 빌드는 서명되지 않으므로, 설치용으로는 `apk/`의 파일을 써 주세요.

## 4. 코드 지도

| 위치 | 하는 일 |
|---|---|
| `app/src/main/…/RadioService.kt` | Media3 미디어 라이브러리 서비스. 차 미디어 화면·운전대·음성 비서가 모두 이 재생을 조작 |
| `app/src/main/…/pleos/PleosConnect.kt` | **Pleos 연결층 하나**. Pleos가 아닌 차·기기에서는 연결이 안 돼 해당 기능만 꺼짐 |
| `app/src/pleos/…/pleos/` | 실제 SDK 연결: `PleosGear`(기어) · `PleosSpeaker`(차 목소리) · `PleosRecognizer`(음성 인식) · `PleosNavi`(목적지) · `GleoActions`·`GleoCall`(음성 비서 함수 3개) |
| `app/src/pleos/gleo/schema.json` | Gleo 앱 스키마: 다음 방송 · 항로 바꾸기(나라·언어·내용) · 지금 방송 저장 |
| `app/src/main/…/drift/` | '표류'(다음 방송 고르기) · 항로 규칙 |
| `app/src/main/…/trip/` | 국경 판단(두 번 연속 같은 새 나라일 때만) · 가는 길 |
| `app/src/main/…/speech/`, `voice/` | 안내 문장 만들기·말하는 동안 소리 줄이기 · 들은 말 해석(저장 → 나라·언어권·내용 → 다음) |
| `app/src/main/…/camping/`, `energy/` | 캠핑 화면 · 배터리 보호 |
| `app/src/main/…/ParkedGate.kt`, `ParkedAccess.kt` | 주행 중 화면 잠금(차량 UX 제한 정보가 없으면 화면을 열지 않음) |
| `app/src/test/` | 기능마다 '순수 규칙'을 SDK 없이 시험 |

## 5. 개인정보 · 보안

- 차에 저장하는 계정 정보·즐겨찾기 대기열은 Android Keystore(AES-GCM)로 암호화하고 백업에서 제외합니다.
- 화면의 QR에는 세션이나 기기 비밀값을 넣지 않습니다(휴대폰 승인 후 서버에서 교환).
- 위치는 '어느 나라에 있는가' 판단에만 쓰고 서버로 보내지 않습니다.

## 6. 데이터 출처

지도: Natural Earth(공개) · GeoNames(CC BY 4.0) · 수도 이름 현지화: Wikidata(CC0). 방송 목록은 서비스 서버가 공개 인터넷 라디오 목록을 선별해 제공합니다.
