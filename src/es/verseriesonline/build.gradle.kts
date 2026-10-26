plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VerSeriesOnline"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.verseriesonline.net"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vudeoextractor"))
}
