plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Donghua no Sekai"
    className = "DonghuaNoSekai"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("donghuanosekai.com")
        path("/..*")
    }
}
