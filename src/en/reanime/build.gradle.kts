import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Re:ANIME"
    versionCode = 20
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    // Domain index: https://reindex.to/
    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://reanime.cz",
                "https://reanime.bz",
                "https://reanime.wtf",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
