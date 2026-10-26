plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "My Running Man"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://www.myrunningman.com"
    }

    deeplink {
        path("/ep/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
