plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PelisPlusHD"
    className = "Pelisplushd"
    versionCode = 82
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "pelisplus"
    baseUrl = "https://pelisplushd.bz"
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
