plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniSama"
    className = "AniSama"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("fr.anisama.net")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidhideextractor"))
}
