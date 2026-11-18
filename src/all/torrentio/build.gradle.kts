import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torrentio (Torrent / Debrid)"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://torrentio.strem.fun"
    }

    deeplink {
        path("/anime/..*")
    }
}
