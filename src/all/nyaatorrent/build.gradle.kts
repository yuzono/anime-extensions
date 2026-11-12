import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Nyaa (Torrent)"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl {
            custom("https://nyaa.si")
        }
    }
    source {
        name = "Nyaa Sukebein (Torrent)"
        lang = "all"
        baseUrl {
            custom("https://sukebei.nyaa.si")
        }
    }

    deeplink {
        host("nyaa.si")
        path("/view/..*")
    }
}
