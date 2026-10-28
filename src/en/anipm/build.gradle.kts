plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniPM"
    className = "AniPM"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
