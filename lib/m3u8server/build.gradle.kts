plugins {
    alias(kei.plugins.library)
}

android {
    sourceSets {
        named("test") {
            java.directories.clear()
            java.directories.add("test/java")
            kotlin.directories.clear()
            kotlin.directories.add("test/kotlin")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("com.github.NanoHttpd.nanohttpd:nanohttpd:-SNAPSHOT")

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit"))
}
