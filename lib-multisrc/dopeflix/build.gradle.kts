plugins {
    id("lib-multisrc")
}

baseVersionCode = 22

dependencies {
    api(project(":lib:dopeflixextractor"))
}
