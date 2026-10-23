plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jutsu TV"
    className = "JutsuTv"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
