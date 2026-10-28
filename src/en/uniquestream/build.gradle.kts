plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UniqueStream"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://anime.uniquestream.net"
    }
}

dependencies {
    implementation(project(":lib:hlssegment"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
