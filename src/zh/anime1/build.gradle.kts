plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime1.me"
    className = "Anime1"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:bangumiscraper"))
    //noinspection UseTomlInstead
    implementation("com.github.houbb:opencc4j:1.8.1")
}
