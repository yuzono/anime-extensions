plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Okanime"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://www.okanime.xyz"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:vidbomextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
