import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiHaven"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://hentaihaven.xxx"
    }
}

dependencies {
    implementation(project(":lib:hlsdash"))
}
