plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pandrama"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://pandrama.com"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:vkextractor"))
}
