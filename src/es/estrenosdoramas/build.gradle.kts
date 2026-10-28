plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EstrenosDoramas"
    versionCode = 21
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://estrenosdoramas.es"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:vidguardextractor"))
}
