import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget

allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }
    dependencies {
        classpath(libs.gradle.kotlin)
    }
}
