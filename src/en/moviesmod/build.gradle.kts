plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MoviesMod"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        name = "Movies Mod"
        lang = "en"
        baseUrl = "https://moviesmod.zone"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
