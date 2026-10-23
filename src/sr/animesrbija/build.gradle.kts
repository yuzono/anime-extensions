plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Srbija"
    className = "AnimeSrbija"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.animesrbija.com")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
}
