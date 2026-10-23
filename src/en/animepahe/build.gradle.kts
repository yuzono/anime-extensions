plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePahe"
    className = "AnimePahe"
    versionCode = 55
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:hlssegment"))
    implementation(project(":lib:unpacker"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
