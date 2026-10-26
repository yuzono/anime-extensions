plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Goyabu"
    versionCode = 6
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
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
