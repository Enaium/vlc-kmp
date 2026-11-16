plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.enaium.vlc.example.player.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.enaium.vlc.example.player.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir("../src/commonMain/resources")
}

dependencies {
    implementation(project(":examples:player"))
    implementation(libs.sdl.kmp)
    implementation(libs.sdl.kmp.android.jvm)
}
