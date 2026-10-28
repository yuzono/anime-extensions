plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "desu-online"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "pl"
        baseUrl = "https://desu-online.pl"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:sibnetextractor"))
}
