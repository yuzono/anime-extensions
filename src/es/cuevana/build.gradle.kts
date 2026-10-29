plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cuevana"
    versionCode = 68
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "Cuevana3Eu"
        lang = "es"
        baseUrl = "https://wv3.cuevana3.eu"
    }

    deeplink {
        path("/ver-pelicula/..*")
        path("/ver-serie/..*")
        path("/episodio/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in CuevanaEu.kt
    deeplink {
        host("www.cuevana3.eu")
        host("cuevana3.eu")
        path("/ver-pelicula/..*")
        path("/ver-serie/..*")
        path("/episodio/..*")
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:universalextractor"))
}
