plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Debrid Index"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://torrentio.strem.fun"
    }

    deeplink {
        path("/anime/..*")
    }
}
