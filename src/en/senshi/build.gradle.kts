plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Senshi"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://senshi.to"
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
