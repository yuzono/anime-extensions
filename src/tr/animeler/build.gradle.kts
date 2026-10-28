plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animeler"
    versionCode = 37
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://animeler.pw"
    }

    deeplink {
        path("/anime/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in Animeler.kt
    deeplink {
        host("animeler.me")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
}
