plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Gotaku"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://gotaku.to"
    }
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
