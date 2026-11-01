plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jav Guru"
    versionCode = 48
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://jav.guru"
    }

    deeplink {
        path("/.*/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:javcoverfetcher"))
    implementation(project(":lib:luluextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:vidhideextractor"))
}
