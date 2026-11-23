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
    implementation(project(":snw-api"))
    implementation(project(":logic"))
    implementation(project(":diary"))
    implementation("net.dv8tion:JDA:6.4.1") { exclude(module = "opus-java"); exclude(module = "tink") }
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("dev.menonng.snw.bot.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
}

tasks.test { useJUnitPlatform() }
