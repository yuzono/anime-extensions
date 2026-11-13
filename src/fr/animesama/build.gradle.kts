import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Sama"
    versionCode = 35
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "fr"
        baseUrl = "https://anime-sama.to"
    }

    deeplink {
        path("/catalogue/..*")
    }

    deeplink {
        host("anime-sama.to")
        path("/catalogue/..*")
    }
}

dependencies {
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:embed4meextractor"))
}
