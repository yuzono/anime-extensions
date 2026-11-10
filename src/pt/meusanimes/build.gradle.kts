import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Meus Animes"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://meusanimes.club"
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}
