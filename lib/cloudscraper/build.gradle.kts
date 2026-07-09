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
    implementation(project(":lib:cloudflareinterceptor"))
    // Chrome 131 TLS fingerprint spoofing — replaces the failed
    // `impersonator-bctls` (BouncyCastle JSSE) path that the interceptor
    // documented as broken on modern CF edges. See :lib:tlsspoof/TlsSpoof.kt
    // for the rationale and upstream-issue references.
    implementation(project(":lib:tlsspoof"))

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit"))
    // okhttp is compileOnly in PluginLibrary.common bundle; make it available
    // at test time so we can construct HttpUrl / Cookie instances in tests.
    testImplementation(libs.okhttp)
    // kotlinx.serialization.json — used by JsdSolver for JSON parsing;
    // needed at test runtime for all JsdSolver-constructing tests.
    testImplementation(libs.kotlin.json)
    // QuickJS — needed for integration tests that run the real sensor script
    testImplementation(libs.quickjs)
}
