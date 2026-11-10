import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWaves.ru"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://aniwaves.ru"
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:doodextractor"))
}
