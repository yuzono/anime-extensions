plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EmpireStreaming"
    versionCode = 30
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl("https://empire-stream.net") {
            mirrors = listOf("https://empire-streaming.app")
        }
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
}
