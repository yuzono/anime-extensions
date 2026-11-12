import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Meus Animes"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://meusanimes.blog"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
