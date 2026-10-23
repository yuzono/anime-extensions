plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeTake"
    className = "AnimeTake"
    versionCode = 21
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
