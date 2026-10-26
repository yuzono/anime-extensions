plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FRAnime"
    versionCode = 29
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://franime.fr"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:vidoextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vidmolyextractor"))
}
