plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KickAssAnime"
    className = "KickAssAnime"
    versionCode = 62
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("kaa.lt")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
    implementation(project(":lib:playlistutils"))
}
