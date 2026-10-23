plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWorld"
    className = "AniWorld"
    versionCode = 41
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidmolyextractor"))
}
