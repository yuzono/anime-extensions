plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tuktuk Cinema"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "توك توك سينما"
        lang = "ar"
        baseUrl("https://tuktukhd.com") {
            withCustom = true
        }
    }
}

dependencies {
    implementation(project(":lib:megamaxmultiserver"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidbomextractor"))
    implementation(project(":lib:vidlandextractor"))
    implementation(project(":lib:i18n"))
}
