@file:Suppress("DEPRECATION")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

@OptIn(ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

// Public headers come from the pinned libVLC 3.0.24 submodule
// (6de05adcbaf2e8b85fe86aad4169393098628119). The runtime library is supplied
// by the platform (VLC.app/Homebrew, libvlc-dev, the VLC Windows SDK, or the
// Android libvlc AAR).
val vlcHeaders = rootProject.file("vlc/include")
val vlcShimDir = project.file("src/nativeInterop/cinterop")

val vlcLibraryDir: String? = providers.gradleProperty("vlc.library.dir").orNull
    ?: providers.environmentVariable("VLC_LIBRARY_DIR").orNull

kotlin {
    // ==================== JVM ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Android (JVM) ====================
    android {
        namespace = "cn.enaium.vlc"
        compileSdk = 36
        minSdk = 24

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== Kotlin/Native ====================
    macosArm64()
    macosX64()

    linuxX64()
    linuxArm64()

    mingwX64()

    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== Web ====================
    // Both targets share webMain and use HTMLMediaElement. There is no libVLC
    // binary for JavaScript or WebAssembly; capabilities report that.
    js(IR) {
        browser()
    }

    wasmJs {
        browser()
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    // ==================== cinterop for every native target ====================
    // The shared native source set must exist before targets are wired.
    sourceSets.create("nativeMain") {
        dependsOn(sourceSets.getByName("commonMain"))
    }
    val nativeMainSourceSet = sourceSets.getByName("nativeMain")
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").apply {
            defaultSourceSet.dependsOn(nativeMainSourceSet)
            cinterops.create("vlc") {
                defFile(project.file("src/nativeInterop/cinterop/vlc.def"))
                includeDirs(vlcHeaders, vlcShimDir)
                if (vlcLibraryDir != null) {
                    linkerOpts("-L$vlcLibraryDir")
                }
            }
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                implementation(libs.atomicfu)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Android uses the same JNI surface as desktop; only loading and
            // video output differ.
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                api(project(":android-jvm"))
            }
        }

        val webMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
        getByName("jsMain").dependsOn(webMain)
        getByName("wasmJsMain").dependsOn(webMain)
    }

    // ==================== Native tests ====================
    // Only the host target is runnable. The host libVLC prefix is used for
    // linking and as an rpath so the test executable finds the runtime.
    val hostOsName = System.getProperty("os.name").orEmpty().lowercase()
    if (hostOsName.contains("mac") || hostOsName.contains("darwin")) {
        val libraryDir = vlcLibraryDir ?: "/Applications/VLC.app/Contents/MacOS/lib"
        targets.withType<KotlinNativeTarget>().configureEach {
            if (name.startsWith("macos")) {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                    linkerOpts("-L$libraryDir", "-Wl,-rpath,$libraryDir")
                }
            }
        }
    }
    if (hostOsName.contains("linux")) {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val libraryDir = vlcLibraryDir ?: when {
            arch.contains("aarch64") -> "/usr/lib/aarch64-linux-gnu"
            else -> "/usr/lib/x86_64-linux-gnu"
        }
        targets.withType<KotlinNativeTarget>().configureEach {
            if (name.startsWith("linux")) {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                    linkerOpts("-L$libraryDir")
                }
            }
        }
    }
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "vlc-kmp",
        version = null,
    )

    pom {
        name.set("vlc-kmp")
        description.set(
            "Kotlin Multiplatform bindings for libVLC 3.0.24. Desktop and Android " +
                "targets bind the libVLC C ABI through cinterop or JNI; js and wasmJs " +
                "provide the same public playback API on HTMLMediaElement.",
        )
        url.set("https://github.com/Enaium/vlc-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("GPL-2.0-or-later")
                url.set("https://www.gnu.org/licenses/gpl-2.0.html")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/vlc-kmp")
            connection.set("scm:git:git@github.com:Enaium/vlc-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/vlc-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/vlc-kmp/issues")
        }
    }
}

// ==================== wasm: npm package ====================
val npmPackageVersion: String = run {
    val projectVersion = project.version.toString()
    val semver = if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(projectVersion)) {
        projectVersion.substringBeforeLast('.') + "-" + projectVersion.substringAfterLast('.')
    } else {
        projectVersion
    }
    require(Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$").matches(semver)) {
        "Project version '$projectVersion' is not valid npm semver"
    }
    semver
}
val wasmExecutableDir = layout.buildDirectory.dir(
    "compileSync/wasmJs/main/productionExecutable/kotlin",
)
val wasmNpmDir = layout.buildDirectory.dir("npm").get().asFile

val assembleWasmNpm by tasks.registering {
    group = "build"
    description = "Stages the @enaium/vlc-kmp Kotlin/Wasm npm package in build/npm."
    dependsOn(
        "wasmJsProductionExecutableCompileSync",
        "wasmJsProductionExecutableValidateGeneratedByCompilerTypeScript",
    )
    inputs.dir(wasmExecutableDir)
    inputs.property("npmPackageVersion", npmPackageVersion)
    outputs.dir(wasmNpmDir)
    doLast {
        wasmNpmDir.deleteRecursively()
        wasmNpmDir.mkdirs()
        copy {
            from(wasmExecutableDir) {
                include("*.mjs", "*.wasm", "*.d.mts", "*.map")
            }
            into(wasmNpmDir)
        }
        rootProject.file("LICENSE").copyTo(wasmNpmDir.resolve("LICENSE"), overwrite = true)
        wasmNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/vlc-kmp",
              "version": "$npmPackageVersion",
              "description": "Kotlin/Wasm HTMLMediaElement backend for the vlc-kmp API",
              "license": "GPL-2.0-or-later",
              "author": "Enaium",
              "type": "module",
              "keywords": ["vlc", "libvlc", "kotlin", "wasm", "video", "browser"],
              "exports": {
                ".": {
                  "types": "./vlc-kmp-vlc-kmp.d.mts",
                  "import": "./vlc-kmp-vlc-kmp.mjs"
                },
                "./package.json": "./package.json"
              },
              "files": [
                "vlc-kmp-vlc-kmp.mjs",
                "vlc-kmp-vlc-kmp.import-object.mjs",
                "vlc-kmp-vlc-kmp.js-builtins.mjs",
                "vlc-kmp-vlc-kmp.wasm",
                "vlc-kmp-vlc-kmp.wasm.map",
                "vlc-kmp-vlc-kmp.d.mts",
                "LICENSE",
                "README.md"
              ],
              "homepage": "https://github.com/Enaium/vlc-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/vlc-kmp.git"
              },
              "bugs": { "url": "https://github.com/Enaium/vlc-kmp/issues" },
              "publishConfig": {
                "access": "public",
                "registry": "https://registry.npmjs.org/"
              }
            }
            """.trimIndent() + "\n",
        )
        wasmNpmDir.resolve("README.md").writeText(
            """
            # @enaium/vlc-kmp

            Kotlin/Wasm browser backend for [vlc-kmp](https://github.com/Enaium/vlc-kmp).

            It implements the vlc-kmp playback API on an `HTMLVideoElement`; it
            does not bundle libVLC. This is the browser-compatible backend
            described by `VlcCapabilities.browser()`.
            """.trimIndent() + "\n",
        )
    }
}

val publishWasmNpm by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Publishes @enaium/vlc-kmp to npm (token: npm.publish.key or NODE_AUTH_TOKEN)."
    dependsOn(assembleWasmNpm)
    workingDir = wasmNpmDir
    val npmKey = providers.gradleProperty("npm.publish.key")
    val npmTag = providers.gradleProperty("npm.publish.tag").orNull
        ?.takeIf { it.isNotBlank() } ?: "latest"
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: System.getenv("NODE_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties, " +
                    "pass -Pnpm.publish.key=<token>, or set NODE_AUTH_TOKEN",
            )
        environment("NODE_AUTH_TOKEN", key)
        wasmNpmDir.resolve(".npmrc").writeText(
            "//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n",
        )
        val args = mutableListOf("npm", "publish", "--access", "public", "--tag", npmTag)
        if (providers.gradleProperty("npm.provenance").orNull?.toBoolean() == true) {
            args += "--provenance"
        }
        if (providers.gradleProperty("npm.publish.dryRun").orNull?.toBoolean() == true) {
            args += "--dry-run"
        }
        commandLine(args)
    }
}
