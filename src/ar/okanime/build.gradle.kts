plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Okanime"
    className = "Okanime"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.okanime.xyz")
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
