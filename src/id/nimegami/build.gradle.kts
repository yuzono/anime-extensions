import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NimeGami"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://nimegami.id"
    }

    deeplink {
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:synchrony"))
}
