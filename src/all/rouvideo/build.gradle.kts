plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "RouVideo"
    className = "RouVideoFactory"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

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
