import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ShuttleTV"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://shuttletv.su",
                "https://shuttletv.pk",
                "https://shuttletv.me",
                "https://shuttletv.cr",
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
