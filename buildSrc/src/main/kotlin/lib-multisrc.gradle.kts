plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
    id("org.jmailen.kotlinter")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "eu.kanade.tachiyomi.multisrc.${project.name}"

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
        }
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
    implementation(project(":core"))
}

tasks {
    preBuild {
        dependsOn(lintKotlin)
    }

    if (System.getenv("CI") != "true") {
        lintKotlin {
            dependsOn(formatKotlin)
        }
    }
}

tasks.register("printDependentExtensions") {
    doLast {
        project.printDependentExtensions()
    }
}
