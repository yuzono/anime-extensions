import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Samehadaku"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://v2.samehadaku.how"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
