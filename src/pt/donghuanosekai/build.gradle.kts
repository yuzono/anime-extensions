plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Donghua no Sekai"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://donghuanosekai.com"
    }

    deeplink {
        path("/..*")
    }
}
