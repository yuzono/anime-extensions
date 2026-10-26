plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniPlay"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "it"
        versionId = 2 // Source was rewritten in Svelte
        baseUrl = "https://aniplay.co"
    }

    deeplink {
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
