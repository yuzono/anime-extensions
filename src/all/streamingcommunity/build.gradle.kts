plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "StreamingCommunity"
    className = "StreamingCommunityFactory"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:i18n"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
