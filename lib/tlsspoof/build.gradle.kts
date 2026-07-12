plugins {
    alias(kei.plugins.library)
}

dependencies {
    // Conscrypt (Chromium's JSSE provider) — the Maven-published build differs
    // from Android's bundled Conscrypt and ships GREASE / ALPS / X25519MLKEM768,
    // giving us a Chromium-adjacent JA3 instead of Android's default JA3.
    // This is the closest a pure-JVM library on Android can get to a real
    // Chrome ClientHello without NDK/JNI native code.
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
