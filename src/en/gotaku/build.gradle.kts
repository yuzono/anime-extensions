plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Gotaku"
    className = "Gotaku"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
