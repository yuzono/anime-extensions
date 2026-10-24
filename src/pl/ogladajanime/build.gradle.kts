plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OgladajAnime"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pl"
        baseUrl = "https://ogladajanime.pl"
    }
}
