plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Senshi"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
            "https://senshi.to",
            "https://senshi.wtf",
            )
        }
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
