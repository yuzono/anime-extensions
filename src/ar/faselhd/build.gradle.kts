plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FASELHD"
    versionCode = 31
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "فاصل اعلاني"
        lang = "ar"
        baseUrl("https://www.faselhds.biz") {
            withCustom = true
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:synchrony"))
}
