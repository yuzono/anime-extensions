import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ShuttleTV"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://shuttletv.pk",
                "https://shuttletv.cr",
                "https://shuttletv.ga",
                "https://shuttletv.me",
            )
        }
    }

    deeplink {
        host("shuttletv.su")
        host("www.shuttletv.su")
        host("shuttletv.pk")
        host("www.shuttletv.pk")
        host("shuttletv.me")
        host("www.shuttletv.me")
        host("shuttletv.cr")
        host("www.shuttletv.cr")
        path("/watch/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
