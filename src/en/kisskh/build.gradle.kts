import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KissKH"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://kisskh.ovh",
                "https://kisskh.do",
                "https://kisskh.co",
                "https://kisskh.id",
                "https://kisskh.la",
                "https://kisskh.is",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
