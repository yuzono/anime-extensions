plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikoto"
    versionCode = 0
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        // https://anikoto.site/#domains and https://megaplay.buzz/domains
        baseUrl("https://anikototv.to") {
            mirrors = listOf(
                "https://anikoto.bz",
                "https://anikoto.cz",
                "https://anikoto.me",
                "https://anikoto.net",
                "https://anikototv.se",
            )
        }
    }
}
