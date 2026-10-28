plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anitube"
    versionCode = 31
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl("https://www.anitube.vip") {
            withCustom = true
        }
    }

    deeplink {
        host("anitube.vip")
        host("www.anitube.vip")
        path("/..*/..*")
    }
}
