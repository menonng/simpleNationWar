import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val javaVersion = (property("java_version") as String).toInt()

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.flatlaf)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("dev.menonng.snw.panel.MainKt")
    applicationName = "snw-panel"
}

tasks.test { useJUnitPlatform() }

// 더블클릭으로 실행할 수 있는 jar 하나(의존성 포함): panel/build/libs/snw-panel.jar
tasks.register<Jar>("fatJar") {
    archiveFileName.set("snw-panel.jar")
    manifest { attributes["Main-Class"] = "dev.menonng.snw.panel.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
}
tasks.named("build") { dependsOn("fatJar") }
