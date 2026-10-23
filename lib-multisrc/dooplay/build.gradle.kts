plugins {
    alias(kei.plugins.multisrc)
}

yuzono {
    baseVersionCode = 4
    libVersion = "14"

    deeplink {
        path("/.*/..*")
    }
}
