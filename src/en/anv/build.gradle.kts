import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anv"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            custom("https://anv.to")
        }
    }

    deeplink {
        host("anv.to")
        host("www.anv.to")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:m3u8server"))
}
