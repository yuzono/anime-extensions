plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlayer"
    className = "AnimePlayer"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animeplayer.com.br"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
