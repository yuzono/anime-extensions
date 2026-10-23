plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Debrid Index"
    className = "DebridIndex"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("torrentio.strem.fun")
        path("/anime/..*")
    }
}
