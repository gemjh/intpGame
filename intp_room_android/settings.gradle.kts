pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "intp_room_android"

// engine: 순수 Kotlin/JVM 게임 규칙 (Android SDK 없이 테스트 가능)
// app:    Jetpack Compose 화면 + 세이브 파일 위치
include(":engine", ":app")
