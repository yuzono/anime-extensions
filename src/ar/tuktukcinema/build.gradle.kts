import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tuktuk Cinema"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "توك توك سينما"
        lang = "ar"
        baseUrl {
            custom("https://tuktukhd.com")
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
