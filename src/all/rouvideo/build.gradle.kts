plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "RouVideo"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    listOf("all", "zh", "en", "vi").forEach {
        source {
            name = "肉視頻"
            lang = it
            baseUrl = "https://rou.video/home"
        }
    }

    deeplink {
        host("rou.video")
        path("/t/.*")
        path("/v/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:i18n"))
}
