plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniHQ"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://anihq.cc"
    }

    deeplink {
        path("/anime-show/..*")
        path("/watch/..*")
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:pixeldrainextractor"))
}
