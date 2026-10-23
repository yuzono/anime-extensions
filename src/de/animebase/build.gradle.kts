plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Base"
    className = "AnimeBase"
    versionCode = 47
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
