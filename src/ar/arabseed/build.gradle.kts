import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Arab Seed"
    versionCode = 33
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "عرب سيد"
        lang = "ar"
        baseUrl = "https://m.asd.homes"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
}
