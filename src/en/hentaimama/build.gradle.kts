import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiMama"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://hentaimama.io"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
