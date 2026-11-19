plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.loom) apply false
}

tasks.register("buildAll") {
    group = "build"
    description = "순수 모듈 테스트와 mod 빌드를 함께 돌린다."
    dependsOn(":snw-api:build", ":logic:test", ":diary:test", ":mod:build")
}
