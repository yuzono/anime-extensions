plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "123Anime"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        // Mirrors verified live; others 301 to the hub
        baseUrl("https://123animehub.cc") {
            mirrors = listOf("https://123anime.la")
        }
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
