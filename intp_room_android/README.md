# INTP 키우기 — 안드로이드 앱 (intp_room_android)

웹 버전(저장소 루트의 `app/engine` 파이썬 엔진 + `room_editor` 방 화면)을 안드로이드 앱으로 옮긴 프로젝트입니다.
서버 없이 기기 안에서 혼자 도는 오프라인 게임이고, 세이브는 앱 내부 저장소에 1개 저장됩니다.

이번 버전(0.2)은 **웹 버전 2026-10-11 빌드(요구사항명세서 261011 1차 범위)** 를 반영했습니다.

## 1. 구조

```
intp_room_android/
├─ settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle/wrapper/
├─ engine/                         순수 Kotlin/JVM — 게임 규칙 (Android SDK 없이 테스트 가능)
│  └─ src/main/kotlin/com/example/introom/engine/
│     ├─ Models.kt                 GameState 등 데이터 모델 (app/engine/models.py)
│     ├─ World.kt                  날씨·불빛·낮달·귀환·고양이 = 세계의 사실 (world.py)
│     ├─ Content.kt                타일맵·오브젝트·텍스트 (content.py)
│     ├─ WindowContent.kt          창문 148문장 + 출현 조건 규칙 (window_content.py, 자동 생성)
│     ├─ Window.kt                 창문 문장 선정·관찰 이력·사실 플래그·지식 (window.py)
│     ├─ Engine.kt                 상호작용 처리·시간 진행·스냅샷 (engine.py)
│     └─ Storage.kt                세이브 JSON 변환 (storage.py)
│  └─ src/test/.../EngineTest.kt   엔진 테스트 26개 (tests/test_engine.py 포팅 + 엔딩 경로)
│  └─ src/test/.../WindowTest.kt   창문 테스트 27개 (tests/test_window.py 포팅)
├─ app/                            Jetpack Compose 화면
│  └─ src/main/
│     ├─ assets/                   room.jpg, 가구 PNG 9종, character/ 스프라이트 20장, layout.json
│     ├─ kotlin/com/example/introom/
│     │  ├─ MainActivity.kt
│     │  ├─ ui/RoomLayout.kt       layout.json 로더 (editor.js)
│     │  ├─ ui/RoomScreen.kt       방 화면·이동·길찾기·대화상자 (game.js + game-interact.js)
│     │  ├─ ui/GameViewModel.kt    엔진 호출 → 화면 상태 (game-interact.js)
│     │  ├─ ui/Theme.kt
│     │  └─ data/GameRepository.kt 세이브 파일 위치 (filesDir/intp_room_save.json)
│     └─ res/ (아이콘·테마·문자열)
└─ tools/gen_window_content.py     window_content.py → WindowContent.kt 생성기
```

웹 버전 파일과 1:1로 대응하도록 나눴고, 콘텐츠 문구는 한 글자도 바꾸지 않았습니다.

## 2. 이번 버전에서 바뀐 점 (0.1 → 0.2)

| 구분 | 0.1 (이전 안드로이드) | 0.2 (지금) |
|---|---|---|
| 창문 버튼 | 잠깐 보기 / 관찰하기 → 2회 관찰 후 바다·하늘·앞마당·횟집 | 처음부터 **바다·하늘·횟집·강아지·앞마당 보기 + 그냥 바라본다** (+ 쌍안경) |
| 창문 문장 | 고정 문장 몇 개 | 검수 시트 **148문장** 중 조건(시간/기록/지식)에 맞는 것을 가중치로 1개 |
| 기록 | 관찰/정보/가설 | + 문장별 관찰 이력, 사실 플래그, 지식(접함) |
| 기존 이벤트 | 밤바다 불빛·낮달·귀환·고양이 | 그대로 유지. 그날 처음 그 대상을 볼 때 우선 표시 |
| 해질녘 "예쁘네" 고정 문장 | 있음 | 삭제 (148문장의 해질녘 문장으로 대체) |
| 이동 | 목표 지점으로 직선 이동 → 이삿짐 상자 등에 막힘 | 웹 버전과 같은 **격자 BFS 길찾기** |
| 세이브 | version 1 | version 2 (v1 세이브는 그대로 불러와지고 새 필드는 빈 값으로 시작) |
| 에셋 | — | 웹 버전 room_editor/assets 의 최신 그림(*-new.png)과 my-layout-261008.json |

엔딩 조건은 같습니다: **밤바다 불빛 관찰 4회 이상 + 가설 재검토 1회 이상** → 문이 "확인하러 간다"로 바뀜.

## 3. 빌드 / 실행

Android Studio(Koala 이상)에서 `intp_room_android` 폴더를 열고 `app` 을 실행하면 됩니다.

- Gradle 8.9 (wrapper 포함), Android Gradle Plugin 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00
- compileSdk/targetSdk 34, minSdk 26, JDK 17

명령줄:

```bash
cd intp_room_android
./gradlew :engine:test        # 엔진 테스트 (Android SDK 불필요)
./gradlew :app:assembleDebug  # APK → app/build/outputs/apk/debug/
```

웹 버전의 창문 문장이 바뀌면 저장소 루트에서 생성기를 다시 돌립니다:

```bash
python3 intp_room_android/tools/gen_window_content.py
```

## 4. 확인한 것 / 못 한 것

- `engine` 모듈: Gradle로 컴파일하고 테스트 53개(EngineTest 26 + WindowTest 27)가 전부 통과했습니다.
- 길찾기: RoomScreen.kt의 길찾기 코드를 그대로 떼어 실제 layout.json으로 JVM에서 돌려 봤습니다.
  시작 위치에서 가구 9종 + 문 모두 상호작용 거리까지 도착합니다. 이전 직선 이동은 창문·메모벽·책장·라디오에 도달하지 못했습니다.
- `app` 모듈: 작업 환경에서 Google Maven(dl.google.com) 접속이 막혀 있어 **APK 빌드와 실기기 실행은 확인하지 못했습니다.**
  Android Studio에서 처음 빌드할 때 오류가 나면 그 메시지를 알려 주세요.
