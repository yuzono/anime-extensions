import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "XAnime"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://xanime.me",
                "https://xanime.app",
            )
        }
    }

    deeplink {
        path("/title/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
