plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "asia2tv"
    versionCode = 39
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "Asia2TV"
        lang = "ar"
        baseUrl = "https://ww1.asia2tv.pw"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidbomextractor"))
}
