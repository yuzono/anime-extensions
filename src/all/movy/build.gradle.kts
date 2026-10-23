plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Movy"
    className = "Movy"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    deeplink {
        host("movy.sx")
        host("www.movy.sx")
        path("/movie/..*")
        path("/tv/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
