plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Goyabu"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://goyabu.io"
    }

    deeplink {
        path("/..*")
        path("/anime/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in Goyabu.kt
    deeplink {
        host("goyabu.to")
        path("/..*")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
