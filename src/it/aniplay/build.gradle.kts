plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniPlay"
    className = "AniPlay"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("aniplay.co")
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
