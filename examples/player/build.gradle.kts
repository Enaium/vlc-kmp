@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val hostOsName = System.getProperty("os.name").orEmpty().lowercase()
val defaultVlcLibraryDir: String? = when {
    hostOsName.contains("mac") -> "/Applications/VLC.app/Contents/MacOS/lib"
        .takeIf { File(it, "libvlc.dylib").isFile }
        ?: listOf("/opt/homebrew/lib", "/usr/local/lib")
            .firstOrNull { File(it, "libvlc.dylib").isFile }

    hostOsName.contains("linux") -> listOf(
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib/aarch64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
    ).firstOrNull { File(it, "libvlc.so").isFile }

    else -> null
}

val vlcLibraryDir: String? = providers.gradleProperty("vlc.library.dir").orNull
    ?: providers.environmentVariable("VLC_LIBRARY_DIR").orNull
    ?: defaultVlcLibraryDir

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.vlc.example.player.MainKt"
        }
    }

    android {
        namespace = "cn.enaium.vlc.example.player"
        compileSdk = 37
        minSdk = 24

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }
    linuxX64 { binaries.executable() }
    linuxArm64 { binaries.executable() }
    mingwX64 { binaries.executable() }

    wasmJs {
        binaries.executable()
        browser()
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        if (!name.startsWith("androidNative") && vlcLibraryDir != null) {
            binaries.configureEach { linkerOpts("-L$vlcLibraryDir") }
            if (name.startsWith("macos") || name.startsWith("linux")) {
                binaries.configureEach { linkerOpts("-Wl,-rpath,$vlcLibraryDir") }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":vlc-kmp"))
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.atomicfu)
            }
        }

        val playerMain by creating {
            dependsOn(getByName("commonMain"))
        }

        jvmMain {
            dependsOn(playerMain)
        }

        getByName("androidMain") {
            dependsOn(playerMain)
            dependencies {
                implementation(libs.sdl.kmp.android.jvm)
            }
        }

        val nativeMain by creating {
            dependsOn(playerMain)
        }
        listOf("macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64").forEach { target ->
            getByName("${target}Main").dependsOn(nativeMain)
        }

        wasmJsMain {
            dependsOn(playerMain)
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(npm("@enaium/sdl-kmp", libs.versions.sdl.kmp.npm.get()))
                implementation(npm("@enaium/imgui-kmp", libs.versions.imgui.kmp.npm.get()))
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

// ---------------------------------------------------------------------------
// Browser bundle
// ---------------------------------------------------------------------------

val wasmOutput = layout.buildDirectory
    .dir("compileSync/wasmJs/main/developmentExecutable/kotlin")
val browserApp = layout.buildDirectory.dir("browserApp")
val wasmNpmModules = rootProject.layout.buildDirectory.dir("wasm/node_modules")
val wasmGeneratedResources = layout.buildDirectory.dir("generated/wasmResources")

val copyWasmAssets by tasks.registering(Copy::class) {
    dependsOn(":kotlinWasmNpmInstall")
    from(wasmNpmModules.map { it.dir("@enaium/sdl-kmp") }) {
        include("sdl_kmp_glue.js", "sdl_wasm.js", "sdl_wasm.wasm")
    }
    from(wasmNpmModules.map { it.dir("@enaium/imgui-kmp") }) {
        include("imgui_kmp_glue.js", "imgui_kmp_exports.js", "imgui_wasm.js", "imgui_wasm.wasm")
    }
    into(wasmGeneratedResources)
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(copyWasmAssets)
    from(copyWasmAssets)
}

val copyWebAssets by tasks.registering(Copy::class) {
    dependsOn("compileDevelopmentExecutableKotlinWasmJs", copyWasmAssets)
    from("src/commonMain/resources")
    from("src/wasmJsMain/resources")
    from(copyWasmAssets)
    from(wasmOutput) {
        include("**")
    }
    into(browserApp)
}
