/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Loads the bundled `libvlc_jni` for the host OS/architecture from the
 * matching `vlc-kmp-jni-jvm-*` artifact.
 *
 * Falls back to `System.loadLibrary("vlc_jni")` (Android, or a developer build
 * with `-Djava.library.path`). The bridge itself locates libvlc at runtime.
 */
internal object NativeLoader {
    private const val LIB_NAME = "vlc_jni"
    private const val RESOURCE_BASE = "/cn/enaium/vlc/native"

    fun load() {
        // Android packages libvlc_jni.so in the APK's jniLibs; there is no
        // classpath resource lookup. SDLActivity or this fallback loads it.
        if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) {
            System.loadLibrary(LIB_NAME)
            return
        }
        val resourceError = try {
            loadFromResources()
            null
        } catch (e: UnsatisfiedLinkError) {
            e
        }
        if (resourceError == null) return

        try {
            System.loadLibrary(LIB_NAME)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Unable to load $LIB_NAME: ${resourceError.message} " +
                    "and System.loadLibrary(\"$LIB_NAME\") failed: ${e.message}",
            )
        }
    }

    private fun loadFromResources() {
        val classifier = detectClassifier()
        val prefix = if (classifier.startsWith("windows")) "" else "lib"
        val libFile = "$prefix$LIB_NAME.${libExtension()}"
        val resourcePath = "$RESOURCE_BASE/$classifier/$libFile"
        val stream = NativeLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw UnsatisfiedLinkError(
                "Resource $resourcePath not found. " +
                    "os=${System.getProperty("os.name")}, arch=${System.getProperty("os.arch")}, " +
                    "classifier=$classifier",
            )
        val bytes = stream.use { it.readBytes() }
        val target = extractToTemp(bytes, libFile)
        try {
            System.load(target.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load $libFile from $resourcePath " +
                    "(os=${System.getProperty("os.name")}, arch=${System.getProperty("os.arch")}): ${e.message}",
            )
        }
    }

    private fun extractToTemp(bytes: ByteArray, libFile: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "vlc-kmp-$hex")
        tempDir.mkdirs()
        val target = File(tempDir, libFile)
        if (!target.isFile || target.length().toInt() != bytes.size) {
            val tmp = File.createTempFile(libFile, ".part", tempDir)
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun detectClassifier(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        val os = when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> "darwin"
            osName.contains("win") -> "windows"
            else -> error("Unsupported OS for vlc-kmp JVM artifact: $osName")
        }
        val arch = when (osArch) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported CPU architecture for vlc-kmp JVM artifact: $osArch")
        }
        return "$os-$arch"
    }

    private fun libExtension(): String = when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") -> "dll"
        System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "dylib"
        else -> "so"
    }
}
