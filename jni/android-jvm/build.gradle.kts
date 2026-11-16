/*
 * Android library packaging the per-ABI libvlc_jni bridge for the four
 * Android ABIs. The official org.videolan.android:libvlc-all artifact is an
 * api dependency, so the libvlc runtime and plugins for arm64-v8a,
 * armeabi-v7a, x86 and x86_64 arrive with the AAR.
 *
 * Set VLC_ANDROID_LIBS_DIR to a directory containing <abi>/libvlc.so to bundle
 * a locally built runtime instead (the official artifact remains on the
 * classpath unless excluded by the consumer).
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

// ---------------------------------------------------------------------------
// Toolchain discovery
// ---------------------------------------------------------------------------

fun localSdkDir(): String? {
    val f = rootProject.file("local.properties")
    if (!f.isFile) return null
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("sdk.dir=") }
        ?.substringAfter('=')
        ?.trim()
}

fun androidNdkPath(): String? {
    val home = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: localSdkDir()
        ?: System.getProperty("user.home") + "/Android/Sdk"
    val ndkDir = File(home, "ndk")
    if (!ndkDir.isDirectory) return null
    return ndkDir.listFiles()
        ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+.*")) }
        ?.sortedBy { it.name }
        ?.lastOrNull()
        ?.absolutePath
}

fun resolveCmakeExecutable(): String {
    val exeName = if (OperatingSystem.current().isWindows) "cmake.exe" else "cmake"
    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }
    listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/opt/local/bin").forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }
    return exeName
}

fun hostPrebuiltDir(): String {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX && arch.contains("aarch64") -> "darwin-arm64"
        os.isMacOsX -> "darwin-x86_64"
        os.isLinux && arch.contains("aarch64") -> "linux-aarch64"
        os.isLinux -> "linux-x86_64"
        os.isWindows -> "windows-x86_64"
        else -> error("Unsupported host for android-jvm build: $os/$arch")
    }
}

fun resolveNinjaExecutable(ndk: String): String {
    listOf(
        "$ndk/prebuilt/${hostPrebuiltDir()}/bin/ninja",
        "$ndk/build/tools/ninja",
    ).forEach { candidate ->
        val f = File(candidate)
        if (f.isFile && f.canExecute()) return candidate
    }
    return "ninja"
}

val cmakeExecutable: String by lazy { resolveCmakeExecutable() }
val ndkPath: String? = androidNdkPath()
val ninjaExecutable: String by lazy { ndkPath?.let { resolveNinjaExecutable(it) } ?: "ninja" }

val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

// ---------------------------------------------------------------------------
// Android library
// ---------------------------------------------------------------------------

android {
    namespace = "cn.enaium.vlc.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    // Runtime libvlc.so + plugins for all four Android ABIs.
    api("org.videolan.android:libvlc-all:3.7.7")
}

// ---------------------------------------------------------------------------
// Native: build libvlc_jni.so for every ABI with the NDK.
// ---------------------------------------------------------------------------

val jniSourceDir = rootProject.file("jni").absolutePath
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")

val prebuiltLibvlcDir: File? =
    (System.getenv("VLC_ANDROID_LIBS_DIR")
        ?: providers.gradleProperty("vlc.android.libs.dir").orNull)
        ?.let(::File)
        ?.takeIf { it.isDirectory }

abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cmakeBuildRoot: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val externalLibvlcDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = File(cmakeBuildRoot.get())
        outputDir.get().asFile.deleteRecursively()
        abis.get().values.forEach { abi ->
            val src = File(root, "$abi/libvlc_jni.so")
            if (!src.isFile) {
                throw GradleException("Missing JNI bridge for $abi: $src")
            }
            val dstDir = File(outputDir.get().asFile, abi)
            dstDir.mkdirs()
            src.copyTo(File(dstDir, "libvlc_jni.so"), overwrite = true)
            externalLibvlcDir.orNull?.let { directory ->
                val libvlc = File(directory.asFile, "$abi/libvlc.so")
                if (libvlc.isFile) {
                    libvlc.copyTo(File(dstDir, "libvlc.so"), overwrite = true)
                }
            }
        }
    }
}

ndkPath?.let { ndk ->
    val toolchain = "$ndk/build/cmake/android.toolchain.cmake"

    androidAbis.values.forEach { abi ->
        val buildDir = layout.buildDirectory.dir("cmake-jni/$abi")

        val configureTask = tasks.register<Exec>("configureJniLib_$abi") {
            doFirst {
                buildDir.get().asFile.mkdirs()
            }
            workingDir = buildDir.get().asFile
            commandLine(
                cmakeExecutable,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
                jniSourceDir,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-24",
                "-DANDROID_STL=c++_static",
                "-DANDROID_JNI=ON",
                "-DVLC_SOURCE_DIR=${rootProject.file("vlc").absolutePath}",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${buildDir.get().asFile.absolutePath}",
                "-UJNI_INCLUDE_DIR",
                "-UJNI_INCLUDE_DIR_PLATFORM",
            )
        }

        tasks.register<Exec>("buildJniLib_$abi") {
            dependsOn(configureTask)
            workingDir = buildDir.get().asFile
            commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
            inputs.files(
                rootProject.file("jni/CMakeLists.txt"),
                rootProject.file("jni/vlc_jni.cpp"),
            )
            inputs.dir(rootProject.file("vlc/include"))
            outputs.file(buildDir.map { it.file("libvlc_jni.so") })
        }
    }
}

val prepareJniLibs = tasks.register<PrepareJniLibsTask>("prepareJniLibs") {
    outputDir.set(generatedJniLibs)
    abis.set(androidAbis)
    cmakeBuildRoot.set(layout.buildDirectory.dir("cmake-jni").map { it.asFile.absolutePath })
    externalLibvlcDir.set(prebuiltLibvlcDir)
}

prepareJniLibs.configure {
    androidAbis.values.forEach { abi ->
        dependsOn("buildJniLib_$abi")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

// ---------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "vlc-kmp-android-jvm",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("vlc-kmp-android-jvm")
        description.set(
            "Android AAR with the per-ABI libvlc_jni bridge used by vlc-kmp's Android target. " +
                "Depends on org.videolan.android:libvlc-all for the runtime.",
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
