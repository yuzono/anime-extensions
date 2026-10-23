plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TR Anime Izle"
    className = "TRAnimeIzle"
    versionCode = 38
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.tranimeizle.io")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
    implementation(project(":lib:youruploadextractor"))
}
