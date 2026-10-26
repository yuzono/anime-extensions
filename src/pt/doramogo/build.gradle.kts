plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Doramogo"
    versionCode = 21
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://doramogo.com"
    }

    deeplink {
        path("/dorama/..*")
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
