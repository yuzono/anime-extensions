plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MissAV"
    versionCode = 34
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "all"
        baseUrl {
            mirrors(
                "https://missav.live",
                "https://missav.ai",
                "https://missav.ws",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:javcoverfetcher"))
    implementation(project(":lib:m3u8server"))
}
