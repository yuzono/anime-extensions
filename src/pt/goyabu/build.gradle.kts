plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Goyabu"
    className = "Goyabu"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("goyabu.io")
        path("/..*")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
