plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes Online CC"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://animesonlinecc.to"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
