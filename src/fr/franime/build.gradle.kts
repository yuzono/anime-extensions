import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FRAnime"
    versionCode = 32
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "fr"
        baseUrl = "https://franime.fr"
    }

    deeplink {
        host("franime.fr")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:embed4meextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:youruploadextractor"))
}
