plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Katanime"
    className = "Katanime"
    versionCode = 25
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:cryptoaes"))
}
