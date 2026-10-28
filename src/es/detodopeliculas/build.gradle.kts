plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DeTodoPeliculas"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        name = "DeTodo Peliculas"
        lang = "es"
        baseUrl = "https://detodopeliculas.nu"
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:voeextractor"))
}
