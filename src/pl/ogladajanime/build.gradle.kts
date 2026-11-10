import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OgladajAnime"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pl"
        baseUrl = "https://ogladajanime.pl"
    }
}
