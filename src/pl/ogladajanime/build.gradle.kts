import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OgladajAnime"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "pl"
        baseUrl = "https://ogladajanime.pl"
    }
}
