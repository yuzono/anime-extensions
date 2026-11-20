import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jellyfin"
    // Remove when moving to a @Source class: with className set, KSP gets no source { } blocks and fails with
    // "@Source found but no source {} blocks"
    className = "JellyfinFactory"
    versionCode = 33
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    // Metadata only: JellyfinFactory creates the sources, three by default, with IDs from their position
    source {
        name = "Jellyfin (1)"
        lang = "all"
        id = 9033626646636195598L
        baseUrl = ""
    }
    source {
        name = "Jellyfin (2)"
        lang = "all"
        id = 4904658814710554862L
        baseUrl = ""
    }
    source {
        name = "Jellyfin (3)"
        lang = "all"
        id = 7533223929553449342L
        baseUrl = ""
    }
}

dependencies {
    implementation(project(":lib:hlsdash"))
    implementation("org.apache.commons:commons-text:1.15.0")
}
