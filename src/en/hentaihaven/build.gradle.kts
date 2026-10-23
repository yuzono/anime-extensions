plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiHaven"
    className = "HentaiHaven"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:hlsdash"))
}
