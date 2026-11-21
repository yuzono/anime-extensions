import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Stremio"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://app.strem.io/shell-v4.4"
    }
}

dependencies {
    implementation("org.apache.commons:commons-text:1.15.0")
}
