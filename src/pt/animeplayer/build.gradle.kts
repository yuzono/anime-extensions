import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlayer"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://anreyalp.vip"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
