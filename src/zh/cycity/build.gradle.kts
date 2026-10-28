plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cycity"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "次元城动漫"
        lang = "zh"
        baseUrl = "https://www.cycani.org"
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
