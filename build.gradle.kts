// 루트 빌드 스크립트: 플러그인 버전만 선언하고, 실제 적용은 app 모듈에서 한다.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
