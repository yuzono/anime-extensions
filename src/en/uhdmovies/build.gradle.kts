import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UHD Movies"
    versionCode = 21
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    // Current domain: https://modlist.in/?type=uhdmovies
    source {
        lang = "en"
        baseUrl {
            custom("https://uhdmovies.ceo")
        }
    }
}

dependencies {
    implementation(project(":lib:drivelinkresolver"))
}
