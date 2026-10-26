plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "az-animex"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.az-animex.com"
    }

    deeplink {
        path("/anime/..*")
    }
}
