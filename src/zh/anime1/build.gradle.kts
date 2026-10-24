plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime1.me"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "zh-hant"
        baseUrl = "https://anime1.me"
    }
}

dependencies {
    implementation(project(":lib:bangumiscraper"))
    //noinspection UseTomlInstead
    implementation("com.github.houbb:opencc4j:1.8.1")
}
