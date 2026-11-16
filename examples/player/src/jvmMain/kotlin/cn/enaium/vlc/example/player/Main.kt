/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc.example.player

import java.nio.file.Files

/**
 * Desktop JVM entry point.
 *
 * ```text
 * --file <path>    media file or URL (defaults to the bundled sample.webm)
 * --frames <n>     exit after n frames (headless CI)
 * --no-audio       use the null audio output
 * ```
 */
fun main(args: Array<String>) {
    var file: String? = null
    var frames = Int.MAX_VALUE
    var noAudio = false
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--file" -> file = args.getOrNull(++index)
            "--frames" -> frames = args.getOrNull(++index)?.toIntOrNull() ?: Int.MAX_VALUE
            "--no-audio" -> noAudio = true
            else -> if (file == null) file = args[index]
        }
        index++
    }

    val media = file?.takeIf { it.isNotBlank() } ?: extractSample()
    if (media == null) {
        System.err.println("No media file supplied and sample.mp4 is missing")
        return
    }

    println("vlc-kmp player: $media")
    PlayerApp(media, frames, noAudio).run()
}

private fun extractSample(): String? {
    val stream = PlayerApp::class.java.getResourceAsStream("/sample.mp4") ?: return null
    return try {
        val file = Files.createTempFile("vlc-kmp-sample", ".mp4")
        file.toFile().deleteOnExit()
        stream.use { input -> Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        file.toAbsolutePath().toString()
    } finally {
        stream.close()
    }
}
