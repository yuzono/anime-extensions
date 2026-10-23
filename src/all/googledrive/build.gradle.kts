plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Google Drive"
    className = "GoogleDrive"
    versionCode = 18
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:googledriveextractor"))
}
