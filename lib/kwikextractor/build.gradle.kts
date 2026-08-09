plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:cloudflareinterceptor"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
