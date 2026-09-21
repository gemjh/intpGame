# INTP 키우기 V0.1 - Android (Kotlin / Jetpack Compose)

FastAPI 웹 버전과 같은 게임을 서버 없이 폰 안에서 돌리는 네이티브 앱. 완전 오프라인이며 권한이 필요 없다.

## 열기 / 실행

1. **Android Studio Ladybug(2024.2) 이상**을 설치한다. (JDK 17 이상과 Android SDK 35 는 Android Studio 가 안내해서 설치해 준다.)
2. `File > Open` 으로 이 폴더(`intp_android`)를 연다. Gradle Sync 가 끝날 때까지 기다린다.
3. 에뮬레이터나 USB 디버깅을 켠 폰을 고르고 `Run ▶`.

명령줄로 APK 만들기:

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk  (폰에 직접 설치해서 테스트 가능)
```

## Android Studio 없이 APK 만들기 (GitHub Actions)

1. GitHub 에 새 저장소를 만들고 이 폴더의 내용을 **저장소 루트에** 올린다. (`.github/workflows/build-apk.yml` 이 포함돼야 한다.)
2. 저장소의 `Actions` 탭 > `Build APK` > `Run workflow`.
3. 몇 분 뒤 실행 결과 페이지 아래 `Artifacts` 에서 `intp-game-debug-apk` 를 받아 압축을 풀면 `app-debug.apk` 가 나온다.
4. 폰으로 옮겨 설치한다. ("출처를 알 수 없는 앱 설치" 허용이 필요하다.)

이 APK 는 디버그 키로 서명된 테스트용이다. 스토어 배포용은 아래 "배포 전에 할 일"을 따른다.

## 테스트

```bash
./gradlew testDebugUnitTest      # 엔진 테스트 24개 (파이썬 버전과 같은 시나리오)
```

## 구조

```
app/src/main/
  assets/                 actions.json / events.json / objects.json  (웹 버전과 같은 콘텐츠)
  kotlin/com/example/intpgame/
    engine/               순수 Kotlin 게임 엔진 (Android 의존성 없음 -> JVM 에서 바로 테스트)
      Models.kt             콘텐츠/상태/화면용 스냅샷 데이터 클래스
      ContentLoader.kt      JSON -> GameContent (+ 콘텐츠 오류를 시작 시점에 검증)
      GameEngine.kt         행동 -> 이벤트 검사 -> 선택 -> 시간 경과
      StateCodec.kt         세이브 JSON 읽기/쓰기 (깨진/옛 세이브에 안전)
    data/GameRepository.kt  assets 읽기 + 내부 저장소 세이브 (임시 파일 후 교체)
    ui/GameViewModel.kt     엔진 호출, 저장, 화면 상태(StateFlow)
    ui/GameScreen.kt        Compose 화면 (상태 / 장소 / 오브젝트 / 기록 / 이벤트 다이얼로그)
    ui/Theme.kt             밝은 종이 톤 + 다크 테마
    MainActivity.kt
app/src/test/.../GameEngineTest.kt
```

## 콘텐츠 수정

행동/이벤트/장소는 `app/src/main/assets/*.json` 을 고치면 된다. 문법은 웹 버전 README 의 "콘텐츠 추가하기"와 같다.
**웹 버전(`intp_game/app/data/`)과 파일이 복사본으로 따로 있으므로**, 콘텐츠를 바꾸면 양쪽에 같이 반영해야 한다.
잘못된 콘텐츠(없는 행동 참조, 없는 장소 해금 등)는 앱 시작 시 바로 오류로 드러난다.

## 배포(Google Play) 전에 할 일

- `app/build.gradle.kts` 의 `applicationId` 를 본인 것으로 바꾼다 (`com.example.*` 는 스토어에 올릴 수 없다).
- 아이콘 교체 (`res/drawable/ic_launcher_foreground.xml` 은 임시 아이콘).
- 업로드 키로 서명한 `./gradlew bundleRelease` (AAB) 를 만든다. 키스토어(`*.jks`)는 저장소에 올리지 않는다(.gitignore 처리됨).
- 릴리스 빌드로 한 번 끝까지 플레이해 본다.
- 폰트: 기본 시스템 폰트를 쓴다. 픽셀 폰트(예: Galmuri)를 쓰려면 라이선스를 확인하고 `res/font/` 에 넣어 Theme 의 typography 에 지정한다.
