plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SupJav"
    versionCode = 31
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    listOf("en", "ja", "zh").forEach {
        source {
            lang = it
            baseUrl = "https://supjav.com"
        }
    }

    deeplink {
        host("supjav.com")
        path("/..*\\\\.html")
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:playlistutils"))
}
