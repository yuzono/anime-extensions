plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWaves.ru"
    className = "AniWaves"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:doodextractor"))
}
