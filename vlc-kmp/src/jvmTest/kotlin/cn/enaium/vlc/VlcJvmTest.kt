/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VlcJvmTest {

    private fun samplePath(): String =
        checkNotNull(javaClass.getResource("/sample.mp4")) { "sample.mp4 missing from test resources" }
            .toURI()
            .path

    @Test
    fun `version and capabilities are reported`() {
        assertTrue(Vlc.version.isNotBlank())
        val engine = Vlc.create(listOf("--vout=dummy", "--aout=dummy"))
        try {
            assertEquals(Vlc.platform, engine.capabilities.platform)
            assertTrue(engine.capabilities.nativeLibVlc)
            assertContains(engine.capabilities.protocols, "https")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `media metadata and tracks are readable`() {
        val engine = Vlc.create(listOf("--vout=dummy", "--aout=dummy"))
        try {
            val media = engine.createMedia(samplePath())
            try {
                assertContains(media.mrl.orEmpty(), "sample.mp4")
                assertTrue(media.parse(10_000))
                assertTrue(media.tracks().isNotEmpty(), "expected at least one track")
            } finally {
                media.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `player emits a playing event`() = runBlocking {
        val engine = Vlc.create(listOf("--vout=dummy", "--aout=dummy"))
        val media = engine.createMedia(samplePath())
        val player = engine.createMediaPlayer()
        try {
            player.media = media
            val playing = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) {
                    player.events.first { it is VlcEvent.PlayerPlaying }
                }
            }
            player.play()
            playing.await()
        } finally {
            player.close()
            media.close()
            engine.close()
        }
    }

    @Test
    fun `playback restarts after end`() = runBlocking {
        val engine = Vlc.create(listOf("--vout=dummy", "--aout=dummy"))
        val media = engine.createMedia(samplePath())
        val player = engine.createMediaPlayer()
        try {
            player.media = media
            player.play()
            withTimeout(20_000) {
                while (player.duration <= 0 && player.state != VlcState.Error) delay(50)
            }
            assertTrue(player.duration > 0, "duration should be known")
            player.time = (player.duration - 300).coerceAtLeast(1)
            withTimeout(20_000) {
                while (player.state != VlcState.Ended && player.state != VlcState.Error) delay(50)
            }
            assertTrue(player.state == VlcState.Ended, "expected the media to reach its end")

            // Mirror PlayerApp.togglePlayback: libVLC needs stop() before play().
            player.stop()
            player.play()
            withTimeout(20_000) {
                while (player.state != VlcState.Playing &&
                    player.state != VlcState.Paused &&
                    player.state != VlcState.Error
                ) {
                    delay(50)
                }
            }
            assertTrue(player.state != VlcState.Error, "restart reported an error")
            withTimeout(10_000) {
                while (player.time <= 0 && player.state != VlcState.Ended) delay(50)
            }
            assertTrue(player.time > 0 || player.state == VlcState.Ended, "playback did not restart")
        } finally {
            player.close()
            media.close()
            engine.close()
        }
    }

    @Test
    fun `auto video sink negotiates decoded frames`() = runBlocking {
        val engine = Vlc.create(listOf("--aout=dummy", "--avcodec-hw=none"))
        val media = engine.createMedia(samplePath())
        val player = engine.createMediaPlayer()
        val frameWidth = AtomicInteger(0)
        try {
            player.setVideoSink { frame -> frameWidth.set(frame.width) }
            player.media = media
            player.play()
            withTimeout(20_000) {
                while (frameWidth.get() <= 0 && player.state != VlcState.Error) delay(20)
            }
            assertTrue(player.state != VlcState.Error, "libVLC reported an error state")
            assertTrue(frameWidth.get() > 0, "format callback did not produce a frame")
        } finally {
            player.close()
            media.close()
            engine.close()
        }
    }

    @Test
    fun `player plays the sample and advances time`() = runBlocking {
        val engine = Vlc.create(listOf("--vout=dummy", "--aout=dummy", "--no-video-title-show"))
        val media = engine.createMedia(samplePath())
        val player = engine.createMediaPlayer()
        try {
            player.media = media
            player.play()
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
            assertTrue(player.duration > 0, "duration should be known")
            assertTrue(player.time > 0 || player.state == VlcState.Ended, "playback did not advance")
            player.volume = 50
            assertTrue(player.volume >= 0)
        } finally {
            assertDoesNotThrow { player.close() }
            assertDoesNotThrow { media.close() }
            assertDoesNotThrow { engine.close() }
        }
    }
}
