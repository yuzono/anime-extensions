import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Movy"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://www.movy.sx"
    }

    deeplink {
        host("movy.sx")
        host("www.movy.sx")
        path("/movie/..*")
        path("/tv/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
