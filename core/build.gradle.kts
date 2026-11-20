plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "keiyoushi.core"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(libs.bundles.common)
    compileOnly(project(":extensions-lib"))
    implementation(libs.nanohttpd.fork)

    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
    testImplementation(libs.junit)
}
