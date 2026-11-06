import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pornhub"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors("https://www.pornhub.com", "https://www.pornhub.org")
        }
    }

    deeplink {
        path("/view_video\\.php.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
