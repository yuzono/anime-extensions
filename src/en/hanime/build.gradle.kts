plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "hanime.tv"
    versionCode = 24
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://hanime.tv"
    }
}

android {
    // Chicory WASM runtime uses MethodHandle.invoke/invokeExact which requires API 26+;
    // D8 cannot desugar these bytecode patterns. Override the project-wide minSdk (21).
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    //noinspection UseTomlInstead
    implementation("com.dylibso.chicory:runtime:1.7.5")
}
