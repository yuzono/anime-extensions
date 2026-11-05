import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kartoons"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "all"
        baseUrl {
            mirrors(
                "https://kartoons.to",
                "https://kartoons.me",
            )
        }
    }

    deeplink {
        host("kartoons.to")
        host("kartoons.me")
        path("/show/..*")
        path("/movie/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
