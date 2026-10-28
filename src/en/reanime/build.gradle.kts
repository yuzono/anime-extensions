plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Re:ANIME"
    versionCode = 14
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl("https://reanime.to") {
            mirrors = listOf("https://reanime.cz", "https://reanime.wtf")
        }
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
