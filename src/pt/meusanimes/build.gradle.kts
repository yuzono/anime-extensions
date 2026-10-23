plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Meus Animes"
    className = "MeusAnimes"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
