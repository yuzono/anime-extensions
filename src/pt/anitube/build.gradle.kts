import io.github.keiyoushi.gradle.api.ContentWarning

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
