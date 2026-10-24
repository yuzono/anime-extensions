plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anichi"
    versionCode = 0
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        // https://megaplay.buzz/domains
        baseUrl = "https://anichi.to"
    }
}
