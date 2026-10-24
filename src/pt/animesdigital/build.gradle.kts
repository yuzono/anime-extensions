plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes Digital"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animesdigital.org"
    }

    deeplink {
        host("animesdigital.org")
        path("/anime/a/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:unpacker"))
}
