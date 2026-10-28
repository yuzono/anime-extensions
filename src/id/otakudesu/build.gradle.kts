plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OtakuDesu"
    versionCode = 44
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://otakudesu.blog"
    }
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
}
