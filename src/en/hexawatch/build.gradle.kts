import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HexaWatch"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://hexa.su"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
