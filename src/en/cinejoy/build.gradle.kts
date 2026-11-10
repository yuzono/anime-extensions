import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cinejoy"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://cinejoy.pk"
    }

    deeplink {
        host("cinejoy.pk")
        path("/movie/..*")
        path("/series/..*")
        path("/watch/..*")
    }
}

dependencies {
    implementation(project(":lib:hlsdash"))
    implementation(project(":lib:playlistutils"))
}
