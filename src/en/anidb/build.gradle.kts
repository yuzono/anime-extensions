plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniDB"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://anilab2.amdapi.click"
    }

    // Keep in sync with LEGACY_HOST in AniDB.kt
    deeplink {
        host("anidb.app")
        path("/anime/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
