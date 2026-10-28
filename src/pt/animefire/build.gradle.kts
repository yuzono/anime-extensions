plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Fire"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animefire.one"
    }

    // animefire.io and animefire.plus: keep in sync with LEGACY_HOSTS in AnimeFire.kt
    deeplink {
        host("animefire.one")
        host("animefire.io")
        host("animefire.plus")
        path("/anime/..*")
        path("/animes/..*")
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
