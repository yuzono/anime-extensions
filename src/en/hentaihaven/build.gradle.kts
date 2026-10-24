plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiHaven"
    versionCode = 9
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
