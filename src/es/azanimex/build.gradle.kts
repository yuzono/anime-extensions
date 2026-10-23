plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "az-animex"
    className = "Azanimex"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.az-animex.com")
        path("/anime/..*")
    }
}
