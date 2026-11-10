import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "123Anime"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        // Mirrors verified live; others 301 to the hub
        baseUrl {
            mirrors(
                "123animehub.cc (Primary)" to "https://123animehub.cc",
                "123anime.la" to "https://123anime.la",
            )
        }
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
