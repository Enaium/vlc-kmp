/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(ExperimentalWasmJsInterop::class)

package cn.enaium.vlc

import kotlinx.browser.document
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast

internal expect val webPlatformName: String

/**
 * Browser entry point. The common [Vlc.create] factory creates a hidden
 * `<video>` element; [VlcWeb.create] can render into an existing one.
 */
object VlcWeb {
    fun create(video: HTMLVideoElement? = null): VlcEngine = WebVlcEngine(video)
}

actual object Vlc {
    actual val platform: String get() = webPlatformName

    actual val version: String get() = "HTMLMediaElement"

    actual fun create(options: List<String>): VlcEngine = WebVlcEngine(null)
}

private fun newVideoElement(): HTMLVideoElement =
    document.createElement("video").unsafeCast<HTMLVideoElement>().also { video ->
        video.controls = false
        video.setAttribute("data-vlc-kmp", "")
        video.style.setProperty("position", "fixed")
        video.style.setProperty("display", "none")
        document.body?.appendChild(video)
    }

private class WebVlcEngine(externalVideo: HTMLVideoElement?) : VlcEngine {
    private val externalVideo = externalVideo
    private val players = mutableSetOf<WebVlcMediaPlayer>()
    private var closed = false

    override val version: String get() = Vlc.version
    override val capabilities: VlcCapabilities = VlcCapabilities.browser().copy(platform = Vlc.platform)

    override fun createMedia(path: String, options: List<String>): VlcMedia =
        WebVlcMedia(path, fromLocation = false)

    override fun createMediaFromLocation(location: String, options: List<String>): VlcMedia =
        WebVlcMedia(location, fromLocation = true)

    override fun createMediaPlayer(): VlcMediaPlayer {
        check(!closed) { "VLC engine is closed" }
        return WebVlcMediaPlayer(this, externalVideo).also { players += it }
    }

    override fun createMediaList(): VlcMediaList = WebVlcMediaList()

    override fun createMediaListPlayer(): VlcMediaListPlayer = WebVlcMediaListPlayer(this)

    override fun lastError(): String? = null

    override fun close() {
        if (closed) return
        closed = true
        players.toList().forEach(VlcMediaPlayer::close)
        players.clear()
    }

    internal fun forget(player: WebVlcMediaPlayer) {
        players -= player
    }
}

private class WebVlcMedia(
    val location: String,
    val fromLocation: Boolean,
) : VlcMedia {
    override val events: Flow<VlcEvent> = emptyFlow()
    override val mrl: String get() = location
    override val state: VlcState get() = VlcState.NothingSpecial
    override val duration: Long get() = 0
    override val parsedStatus: VlcParsedStatus get() = VlcParsedStatus.Done

    override fun addOption(option: String) = Unit

    override fun getMeta(meta: VlcMeta): String? = null

    override fun setMeta(meta: VlcMeta, value: String): Boolean = false

    override fun parse(timeoutMs: Long): Boolean = true

    override fun tracks(): List<VlcTrackInfo> = emptyList()

    override fun retain(): VlcMedia = WebVlcMedia(location, fromLocation)

    override fun close() = Unit
}

private class WebVlcMediaPlayer(
    private val engine: WebVlcEngine,
    externalVideo: HTMLVideoElement?,
) : VlcMediaPlayer {
    private val ownsElement = externalVideo == null
    private val video: HTMLVideoElement = externalVideo ?: newVideoElement()
    private val flow = MutableSharedFlow<VlcEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val listeners = mutableListOf<Pair<String, (Event) -> Unit>>()
    private var closed = false
    private var mediaRef: VlcMedia? = null

    init {
        video.playsInline = true
        video.preload = "auto"
        listen("loadstart") { emit(VlcEvent.PlayerOpening) }
        listen("loadedmetadata") {
            emit(VlcEvent.PlayerLengthChanged(duration))
            emit(VlcEvent.PlayerMediaChanged((mediaRef as? WebVlcMedia)?.mrl))
        }
        listen("durationchange") { emit(VlcEvent.PlayerLengthChanged(duration)) }
        listen("timeupdate") {
            emit(VlcEvent.PlayerTimeChanged(time))
            emit(VlcEvent.PlayerPositionChanged(position))
        }
        listen("play") { emit(VlcEvent.PlayerPlaying) }
        listen("pause") {
            if (!video.ended) emit(VlcEvent.PlayerPaused)
        }
        listen("ended") { emit(VlcEvent.PlayerEndReached) }
        listen("error") { emit(VlcEvent.PlayerEncounteredError) }
        listen("volumechange") {
            emit(VlcEvent.PlayerAudioVolume((video.volume * 100).toInt()))
            emit(VlcEvent.PlayerMuted(video.muted))
        }
        listen("seeked") { emit(VlcEvent.PlayerTimeChanged(time)) }
    }

    override val events: Flow<VlcEvent> = flow.asSharedFlow()

    override var media: VlcMedia?
        get() = mediaRef
        set(value) {
            mediaRef = value
            if (value is WebVlcMedia) {
                video.src = value.location
                video.load()
            }
        }

    override val state: VlcState
        get() = when {
            closed -> VlcState.Stopped
            video.ended -> VlcState.Ended
            video.paused -> if (video.currentSrc.isEmpty()) VlcState.NothingSpecial else VlcState.Paused
            else -> VlcState.Playing
        }

    override val isPlaying: Boolean get() = !video.paused && !video.ended

    override var time: Long
        get() = (video.currentTime * 1000.0).toLong()
        set(value) {
            if (value >= 0) video.currentTime = value / 1000.0
        }

    override var position: Float
        get() {
            val duration = video.duration
            return if (duration.isFinite() && duration > 0) {
                (video.currentTime / duration).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        set(value) {
            val duration = video.duration
            if (duration.isFinite() && duration > 0) {
                video.currentTime = duration * value.coerceIn(0f, 1f)
            }
        }

    override val duration: Long
        get() = if (video.duration.isFinite()) (video.duration * 1000.0).toLong() else 0

    override var rate: Float
        get() = video.playbackRate.toFloat()
        set(value) {
            video.playbackRate = value.toDouble().coerceAtLeast(0.01)
        }

    override var volume: Int
        get() = (video.volume * 100).toInt()
        set(value) {
            video.volume = (value.coerceIn(0, 100) / 100.0)
        }

    override var muted: Boolean
        get() = video.muted
        set(value) {
            video.muted = value
        }

    override var audioTrack: Int
        get() = 0
        set(value) = Unit

    override var videoTrack: Int
        get() = 0
        set(value) = Unit

    override var spuTrack: Int
        get() = 0
        set(value) = Unit

    override val audioTracks: List<VlcTrackDescription> get() = emptyList()
    override val videoTracks: List<VlcTrackDescription> get() = emptyList()
    override val spuTracks: List<VlcTrackDescription> get() = emptyList()

    override val videoSize: VlcSize?
        get() = if (video.videoWidth > 0 && video.videoHeight > 0) {
            VlcSize(video.videoWidth, video.videoHeight)
        } else {
            null
        }

    override val hasVideoOutput: Boolean get() = video.videoWidth > 0
    override val framesPerSecond: Float get() = 0f

    override var title: Int
        get() = 0
        set(value) = Unit

    override val titleCount: Int get() = 0

    override var chapter: Int
        get() = 0
        set(value) = Unit

    override val chapterCount: Int get() = 0

    override var audioDelay: Long
        get() = 0
        set(value) = Unit

    override var spuDelay: Long
        get() = 0
        set(value) = Unit

    override fun play() {
        if (video.src.isEmpty() && media is WebVlcMedia) {
            video.src = (media as WebVlcMedia).location
            video.load()
        }
        video.play()
    }

    override fun setPause(paused: Boolean) {
        if (paused) video.pause() else video.play()
    }

    override fun stop() {
        video.pause()
        video.removeAttribute("src")
        video.load()
        emit(VlcEvent.PlayerStopped)
    }

    override fun setVideoSink(sink: VlcVideoSink): Boolean = false

    override fun setVideoSink(width: Int, height: Int, sink: VlcVideoSink): Boolean = false

    override fun clearVideoSink() = Unit

    override fun setWindow(handle: Long, kind: VlcWindowKind): Boolean = false

    override fun snapshot(path: String, width: Int, height: Int): Boolean = false

    override fun addSlave(type: VlcTrackType, uri: String, select: Boolean): Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        listeners.forEach { (type, listener) -> video.removeEventListener(type, listener) }
        listeners.clear()
        video.pause()
        if (ownsElement) {
            video.removeAttribute("src")
            video.load()
            video.parentNode?.removeChild(video)
        }
        engine.forget(this)
    }

    private fun listen(type: String, handler: (Event) -> Unit) {
        video.addEventListener(type, handler)
        listeners += type to handler
    }

    private fun emit(event: VlcEvent) {
        flow.tryEmit(event)
    }
}

private class WebVlcMediaList : VlcMediaList {
    private val items = mutableListOf<VlcMedia>()

    override val count: Int get() = items.size

    override fun add(media: VlcMedia) {
        items += media
    }

    override fun insert(index: Int, media: VlcMedia) {
        items.add(index.coerceIn(0, items.size), media)
    }

    override fun remove(index: Int) {
        if (index in items.indices) items.removeAt(index)
    }

    override fun mediaAt(index: Int): VlcMedia? = items.getOrNull(index)

    override fun clear() = items.clear()

    override fun close() = items.clear()
}

private class WebVlcMediaListPlayer(
    private val engine: WebVlcEngine,
) : VlcMediaListPlayer {
    private var index = 0
    private var mode = VlcPlaybackMode.Default

    override var mediaList: VlcMediaList? = null
    override var mediaPlayer: VlcMediaPlayer? = null

    override var playbackMode: VlcPlaybackMode
        get() = mode
        set(value) {
            mode = value
        }

    override fun play() {
        val list = mediaList ?: return
        val player = mediaPlayer ?: engine.createMediaPlayer().also { mediaPlayer = it }
        if (list.count == 0) return
        player.media = list.mediaAt(index.coerceIn(0, list.count - 1))
        player.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun next() {
        val count = mediaList?.count ?: 0
        if (count == 0) return
        index = when (mode) {
            VlcPlaybackMode.Repeat -> index
            VlcPlaybackMode.Loop -> (index + 1) % count
            VlcPlaybackMode.Default -> (index + 1).coerceAtMost(count - 1)
        }
        play()
    }

    override fun previous() {
        val count = mediaList?.count ?: 0
        if (count == 0) return
        index = when (mode) {
            VlcPlaybackMode.Repeat -> index
            VlcPlaybackMode.Loop -> (index - 1 + count) % count
            VlcPlaybackMode.Default -> (index - 1).coerceAtLeast(0)
        }
        play()
    }

    override fun close() {
        mediaPlayer?.close()
        mediaPlayer = null
    }
}
