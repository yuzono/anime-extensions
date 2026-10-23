plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SushiAnimes"
    className = "SushiAnimes"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("sushianimes.com.br")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
