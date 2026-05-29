plugins {
    alias(kei.plugins.library)
}

android {
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation("org.mozilla:rhino:1.7.14")
}
