import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Vostfree"
    versionCode = 44
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://vostfree.ws"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
}
