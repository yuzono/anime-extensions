plugins {
    alias(kei.plugins.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets {
        named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
        }
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:4ccb73a3c3334fbbca8a5ffac557aec609f87387")
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}
