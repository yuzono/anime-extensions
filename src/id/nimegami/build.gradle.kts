plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NimeGami"
    className = "NimeGami"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("nimegami.id")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:synchrony"))
}
