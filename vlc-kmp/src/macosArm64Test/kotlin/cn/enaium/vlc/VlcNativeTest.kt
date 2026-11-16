/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.vlc

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.R_OK
import platform.posix.access
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Host-native smoke test. It is skipped when the example media is not present
 * (for example when only the library subproject is checked out).
 */
class VlcNativeTest {

    private fun samplePath(): String? = listOf(
        "examples/player/src/commonMain/resources/sample.mp4",
        "../examples/player/src/commonMain/resources/sample.mp4",
        "../../examples/player/src/commonMain/resources/sample.mp4",
    ).firstOrNull { access(it, R_OK) == 0 }

    @Test
    fun `parse tracks and playback advance`() = runBlocking {
        val path = samplePath() ?: return@runBlocking
        val engine = Vlc.create(listOf("--no-video-title-show", "--aout=dummy", "--avcodec-hw=none"))
        val media = engine.createMedia(path)
        val player = engine.createMediaPlayer()
        val firstFrameWidth = kotlinx.atomicfu.atomic(0)
        try {
            assertTrue(media.parse(10_000), "media parse should complete")
            assertTrue(media.tracks().isNotEmpty(), "expected at least one track")

            player.setVideoSink { frame -> firstFrameWidth.value = frame.width }
            player.media = media
            val playing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { player.events.first { it is VlcEvent.PlayerPlaying } }
            }
            player.play()
            playing.await()
            withTimeout(20_000) {
                while (firstFrameWidth.value <= 0 && player.state != VlcState.Error) delay(20)
            }
            assertTrue(firstFrameWidth.value > 0, "format callback did not produce a frame")
            withTimeout(20_000) {
                while (player.state != VlcState.Playing &&
                    player.state != VlcState.Paused &&
                    player.state != VlcState.Ended &&
                    player.state != VlcState.Error
                ) {
                    delay(50)
                }
            }
            assertTrue(player.state != VlcState.Error, "libVLC reported an error state")
            withTimeout(10_000) {
                while (player.time <= 0 && player.state != VlcState.Ended) delay(50)
            }
            assertTrue(player.time > 0 || player.state == VlcState.Ended, "playback did not advance")
        } finally {
            player.close()
            media.close()
            engine.close()
        }
    }
}
