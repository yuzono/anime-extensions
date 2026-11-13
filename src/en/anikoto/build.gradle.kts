import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikoto"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        // https://anikoto.site/#domains and https://megaplay.buzz/domains
        baseUrl {
            mirrors(
                "https://anikototv.bz",
                "https://anikoto.cz",
                "https://anikoto.me",
                "https://anikoto.net",
                "https://anikototv.se",
            )
        }
    }
}
