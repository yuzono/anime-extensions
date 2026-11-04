plugins {
    alias(kei.plugins.multisrc)
}

yuzono {
    baseVersionCode = 8
    libVersion = "14"

    deeplink {
        path("/..*")
    }
}
