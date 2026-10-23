plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FRAnime"
    className = "FrAnime"
    versionCode = 29
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("franime.fr")
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
