plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "XAnime"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl("https://xanime.me") {
            mirrors = listOf("https://xanime.app")
        }
    }

    deeplink {
        path("/title/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
