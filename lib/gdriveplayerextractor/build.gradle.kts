plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:cryptoaes"))
    implementation(project(":lib:unpacker"))
}
