/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.vlc.example.player.PlayerApp
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Desktop native entry point.
 *
 * `VLC_KMP_FILE` selects the media, `VLC_KMP_FRAMES` limits the frame count,
 * and `VLC_KMP_NO_AUDIO=1` selects the null audio output for headless runs.
 */
fun main() {
    val file = getenv("VLC_KMP_FILE")?.toKString()
        ?: "examples/player/src/commonMain/resources/sample.mp4"
    val frames = getenv("VLC_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val noAudio = getenv("VLC_KMP_NO_AUDIO") != null
    println("vlc-kmp player (native): $file")
    PlayerApp(file, frames, noAudio).run()
}
