plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PelisPlusHD"
    versionCode = 82
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "pelisplus"

    source {
        lang = "es"
        baseUrl = "https://pelisplushd.bz"
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
