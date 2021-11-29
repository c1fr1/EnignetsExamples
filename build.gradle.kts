import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 Should be one of the following depending on your operating system or the platform you are targeting

 x64 Windows - "windows"
 x86 Windows - "windows-x86"
 arm64 Windows - "windows-arm64"

 x64 Linux - "linux"
 x64 Linux - "linux-arm64"
 x64 Linux - "linux-arm32"
 */
val osString = "windows"
val lwjglNatives = "natives-${osString}"

//this can be any valid tag on this repo, or a short commit id. More info at https://jitpack.io/.
val enignetsVersion = "main-SNAPSHOT"

val lwjglVersion = "3.2.3"
val jomlVersion = "1.10.1"

plugins {
    kotlin("jvm") version "1.6.0"
    application
    id("com.github.johnrengelman.shadow") version "7.1.0"
}

group = "c1fr1"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl", "lwjgl-assimp")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-openal")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.joml", "joml", jomlVersion)

    runtimeOnly("org.lwjgl", "lwjgl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-openal", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = lwjglNatives)

    implementation("com.github.c1fr1:Enignets:${enignetsVersion}")

}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("AnimatedCollisionTestMainKt")
}