plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anizm"
    className = "Anizm"
    versionCode = 47
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("anizm.net")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
}
