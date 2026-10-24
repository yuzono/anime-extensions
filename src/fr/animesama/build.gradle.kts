plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Sama"
    versionCode = 32
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://anime-sama.to"
        skipCodeGen = true
    }

    deeplink {
        host("anime-sama.si")
        host("anime-sama.tv")
        path("/catalogue/..*")
    }
}

dependencies {
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:embed4meextractor"))
}
