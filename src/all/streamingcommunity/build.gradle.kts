plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "StreamingCommunity"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    // The published IDs, so a renamed source keeps users' libraries
    source {
        name = "StreamingUnity (Movie)"
        lang = "en"
        id = 665657603807796138L
        baseUrl {
            custom("https://streamingunity.fun")
        }
    }
    source {
        name = "StreamingUnity (Movie)"
        lang = "it"
        id = 502708102217708509L
        baseUrl {
            custom("https://streamingunity.fun")
        }
    }
    source {
        name = "StreamingUnity (Tv)"
        lang = "en"
        id = 349088835984594821L
        baseUrl {
            custom("https://streamingunity.fun")
        }
    }
    source {
        name = "StreamingUnity (Tv)"
        lang = "it"
        id = 7246470104959245892L
        baseUrl {
            custom("https://streamingunity.fun")
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:i18n"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
