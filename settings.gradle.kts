pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "snw"

// snw-api: 다른 모드(zomApo, snw-discord)가 컴파일 시 참조하는 인터페이스. 마인크래프트 타입을 쓰지 않는다.
// logic:   순수 Kotlin 규칙 (닉네임 검사, K/D/A 판정, 영토 규칙, 다각형 병합).
// diary:   생존일지 생성기 (규칙 기반 문장 생성). 디스코드 봇이 쓴다.
// mod:     Fabric 서버 모드.
include(":snw-api", ":logic", ":diary", ":mod")
