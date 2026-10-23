plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "XAnime"
    className = "XAnime"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    deeplink {
        host("xanime.me")
        host("xanime.app")
        path("/title/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
