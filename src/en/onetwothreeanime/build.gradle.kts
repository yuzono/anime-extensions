plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "123Anime"
    className = "OneThreeTwoAnime"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
