plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pi Fansubs"
    versionCode = 31
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://pifansubs.club"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
}
