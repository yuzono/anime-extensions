plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KickAssAnime"
    versionCode = 63
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        // Check domains here: https://kickassanime.cx/
        // Main site. Other domains are redirects to this site, meaning that any search with these domains fail because of non-existant addresses.
        baseUrl("https://kaa.lt") {
            mirrors = listOf(
                "https://kaa.to",
                // Removed https://kaa.si as its certificate expired, leading to SSL errors
                // Removed https://kaa.rs, https://kickass-anime.ro and https://kickass-anime.ru as their domains expired
            )
        }
    }

    deeplink {
        host("kaa.lt")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
    implementation(project(":lib:playlistutils"))
}
