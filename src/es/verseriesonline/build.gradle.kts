plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VerSeriesOnline"
    className = "VerSeriesOnline"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.verseriesonline.net")
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
