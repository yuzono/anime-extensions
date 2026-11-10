import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePahe"
    versionCode = 57
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://animepahe.pw",
                "https://animepahe.com",
                "https://animepahe.org",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:hlssegment"))
    implementation(project(":lib:unpacker"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
