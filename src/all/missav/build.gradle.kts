plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MissAV"
    versionCode = 33
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "all"
        baseUrl("https://missav.live") {
            mirrors = listOf("https://missav.ai", "https://missav.ws")
        }
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:javcoverfetcher"))
    implementation(project(":lib:m3u8server"))
}
