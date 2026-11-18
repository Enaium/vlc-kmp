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
    // Artifact version: <kmp-version>-fixed.<pinned-upstream-version>.
    val kmpVersion = "1.0.0"
    val vlcVersion = "3.0.24"
    version = "$kmpVersion-fixed.$vlcVersion"
}
