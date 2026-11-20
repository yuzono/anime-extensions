import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FASELHD"
    versionCode = 32
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "فاصل اعلاني"
        lang = "ar"
        baseUrl {
            custom("https://www.faselhds.biz")
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:synchrony"))
}
