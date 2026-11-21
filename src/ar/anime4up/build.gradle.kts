import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime4up"
    versionCode = 82
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "Anime4Up"
        lang = "ar"
        baseUrl = "https://w1.anime4up.rest"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidbomextractor"))
    implementation(project(":lib:voeextractor"))
}
