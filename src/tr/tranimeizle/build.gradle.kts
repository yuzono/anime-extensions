plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TR Anime Izle"
    versionCode = 39
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://www.tranimeizle.io"
    }

    deeplink {
        path("/anime/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in TRAnimeIzle.kt
    deeplink {
        host("www.tranimeizle.top")
        host("www.tranimeizle.co")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
    implementation(project(":lib:youruploadextractor"))
}
