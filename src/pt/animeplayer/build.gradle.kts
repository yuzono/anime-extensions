plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlayer"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://animeplayer.com.br"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
