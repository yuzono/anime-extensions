plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anitube"
    className = "Anitube"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("anitube.vip")
        host("www.anitube.vip")
        path("/..*/..*")
    }
}
