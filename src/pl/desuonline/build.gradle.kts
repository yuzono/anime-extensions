plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "desu-online"
    className = "DesuOnline"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://desu-online.pl"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:sibnetextractor"))
}
