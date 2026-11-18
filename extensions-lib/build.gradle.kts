plugins {
    alias(libs.plugins.android.library)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.animeextensions"

    buildFeatures {
        resValues = false
    }
}

dependencies {
    compileOnly(libs.okhttp)
    compileOnly(libs.jsoup)
    compileOnly(libs.injekt.core)
    compileOnlyApi(libs.nanohttpd.app)
    compileOnlyApi(libs.androidx.preference) { isTransitive = false }
}
