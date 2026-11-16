/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

import kotlinx.coroutines.flow.Flow

/**
 * Entry point to the platform backend.
 *
 * ```kotlin
 * val vlc = Vlc.create(listOf("--no-video-title-show"))
 * val media = vlc.createMedia("/path/to/movie.mp4")
 * vlc.createMediaPlayer().use { player ->
 *     player.media = media
 *     player.play()
 * }
 * ```
 */
expect object Vlc {
    /** Human-readable platform identifier: `jvm`, `android`, `macosArm64`, `linuxX64`, `mingwX64`, ... */
    val platform: String

    /** Version reported by the loaded libVLC, or `HTMLMediaElement` on the web. */
    val version: String

    /**
     * Creates an engine. [options] are libVLC command-line arguments, e.g.
     * `--no-video-title-show` or `--avcodec-hw=none`. They are ignored by the
     * browser backend.
     */
    fun create(options: List<String> = emptyList()): VlcEngine
}

interface VlcEngine : AutoCloseable {
    val version: String
    val capabilities: VlcCapabilities

    fun createMedia(path: String, options: List<String> = emptyList()): VlcMedia

    fun createMediaFromLocation(location: String, options: List<String> = emptyList()): VlcMedia

    fun createMediaPlayer(): VlcMediaPlayer

    fun createMediaList(): VlcMediaList

    fun createMediaListPlayer(): VlcMediaListPlayer

    /** Last libVLC error for the calling thread, if any. */
    fun lastError(): String?

    override fun close()
}

interface VlcMedia : AutoCloseable {
    val mrl: String?
    val state: VlcState
    val duration: Long
    val parsedStatus: VlcParsedStatus
    val events: Flow<VlcEvent>

    fun addOption(option: String)

    fun getMeta(meta: VlcMeta): String?

    fun setMeta(meta: VlcMeta, value: String): Boolean

    /**
     * Parses the media and waits up to [timeoutMs] (0 = no timeout) for the
     * parsed status to leave [VlcParsedStatus.Unparsed].
     */
    fun parse(timeoutMs: Long = 0): Boolean

    fun tracks(): List<VlcTrackInfo>

    /** Increments the native reference count. The returned handle must be closed. */
    fun retain(): VlcMedia

    override fun close()
}

interface VlcMediaPlayer : AutoCloseable {
    var media: VlcMedia?
    val events: Flow<VlcEvent>
    val state: VlcState
    val isPlaying: Boolean

    var time: Long
    var position: Float
    val duration: Long
    var rate: Float
    var volume: Int
    var muted: Boolean

    var audioTrack: Int
    var videoTrack: Int
    var spuTrack: Int

    val audioTracks: List<VlcTrackDescription>
    val videoTracks: List<VlcTrackDescription>
    val spuTracks: List<VlcTrackDescription>

    val videoSize: VlcSize?
    val hasVideoOutput: Boolean
    val framesPerSecond: Float

    var title: Int
    val titleCount: Int
    var chapter: Int
    val chapterCount: Int

    var audioDelay: Long
    var spuDelay: Long

    fun play()

    fun setPause(paused: Boolean)

    fun pause() = setPause(true)

    fun stop()

    /**
     * Requests software-decoded frames. libVLC negotiates the frame size with
     * its video output (RV32); call before [play]. The sink is invoked on a
     * libVLC thread and the pixel array is reused.
     */
    fun setVideoSink(sink: VlcVideoSink): Boolean

    /**
     * Requests software-decoded frames at a fixed [width]x[height] in RV32.
     * Prefer [setVideoSink] unless the caller needs a fixed surface size.
     */
    fun setVideoSink(width: Int, height: Int, sink: VlcVideoSink): Boolean

    fun clearVideoSink()

    /** Embeds the video output in a native window handle. */
    fun setWindow(handle: Long, kind: VlcWindowKind): Boolean

    fun snapshot(path: String, width: Int = 0, height: Int = 0): Boolean

    /** Adds an external audio or subtitle slave; [type] must be Audio or Text. */
    fun addSlave(type: VlcTrackType, uri: String, select: Boolean = false): Boolean

    override fun close()
}

interface VlcMediaList : AutoCloseable {
    val count: Int

    fun add(media: VlcMedia)

    fun insert(index: Int, media: VlcMedia)

    fun remove(index: Int)

    fun mediaAt(index: Int): VlcMedia?

    fun clear()

    override fun close()
}

interface VlcMediaListPlayer : AutoCloseable {
    var mediaList: VlcMediaList?
    var mediaPlayer: VlcMediaPlayer?
    var playbackMode: VlcPlaybackMode

    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    override fun close()
}
