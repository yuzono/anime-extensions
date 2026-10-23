plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Fire"
    className = "AnimeFire"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

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
