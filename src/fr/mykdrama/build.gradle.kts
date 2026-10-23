plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MyKdrama"
    className = "MyKdrama"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://mykdrama.co"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vudeoextractor"))
}
