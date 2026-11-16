plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

allprojects {
    group = "cn.enaium.vlc"
    // Pinned libVLC release (3.0.24) plus this library's own revision.
    version = "3.0.24.1"
}
