plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DeTodoPeliculas"
    className = "DeTodoPeliculas"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://detodopeliculas.nu"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:voeextractor"))
}
