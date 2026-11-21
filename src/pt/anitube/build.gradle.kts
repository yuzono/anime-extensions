import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anitube"
    versionCode = 33
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl {
            custom("https://www.anitube.vip")
        }
    }

    deeplink {
        host("anitube.vip")
        host("www.anitube.vip")
        path("/..*/..*")
    }
}
