/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val EVENT_BUFFER = 64

private object EventCodes {
    const val MediaMetaChanged = 0
    const val MediaDurationChanged = 2
    const val MediaParsedChanged = 3
    const val MediaStateChanged = 5
    const val MediaPlayerMediaChanged = 0x100
    const val MediaPlayerNothingSpecial = 0x101
    const val MediaPlayerOpening = 0x102
    const val MediaPlayerBuffering = 0x103
    const val MediaPlayerPlaying = 0x104
    const val MediaPlayerPaused = 0x105
    const val MediaPlayerStopped = 0x106
    const val MediaPlayerEndReached = 0x109
    const val MediaPlayerEncounteredError = 0x10a
    const val MediaPlayerTimeChanged = 0x10b
    const val MediaPlayerPositionChanged = 0x10c
    const val MediaPlayerSeekableChanged = 0x10d
    const val MediaPlayerPausableChanged = 0x10e
    const val MediaPlayerTitleChanged = 0x10f
    const val MediaPlayerSnapshotTaken = 0x110
    const val MediaPlayerLengthChanged = 0x111
    const val MediaPlayerVout = 0x112
    const val MediaPlayerESAdded = 0x114
    const val MediaPlayerESDeleted = 0x115
    const val MediaPlayerESSelected = 0x116
    const val MediaPlayerMuted = 0x119
    const val MediaPlayerUnmuted = 0x11a
    const val MediaPlayerAudioVolume = 0x11b
    const val MediaPlayerChapterChanged = 0x11d
}

private class JvmEventCallback(
    private val flow: MutableSharedFlow<VlcEvent>,
) : VlcEventCallback {
    override fun onEvent(type: Int, longValue: Long, doubleValue: Double, intValue: Int, trackType: Int) {
        val event = when (type) {
            EventCodes.MediaMetaChanged ->
                VlcEvent.MediaMetaChanged(VlcMeta.entries.getOrNull(intValue) ?: VlcMeta.Title)

            EventCodes.MediaDurationChanged -> VlcEvent.MediaDurationChanged(longValue)
            EventCodes.MediaParsedChanged -> VlcEvent.MediaParsedChanged(VlcParsedStatus.fromCode(intValue))
            EventCodes.MediaStateChanged -> VlcEvent.MediaStateChanged(VlcState.fromCode(intValue))
            EventCodes.MediaPlayerMediaChanged -> VlcEvent.PlayerMediaChanged(null)
            EventCodes.MediaPlayerNothingSpecial -> VlcEvent.PlayerNothingSpecial
            EventCodes.MediaPlayerOpening -> VlcEvent.PlayerOpening
            EventCodes.MediaPlayerBuffering -> VlcEvent.PlayerBuffering(doubleValue.toFloat())
            EventCodes.MediaPlayerPlaying -> VlcEvent.PlayerPlaying
            EventCodes.MediaPlayerPaused -> VlcEvent.PlayerPaused
            EventCodes.MediaPlayerStopped -> VlcEvent.PlayerStopped
            EventCodes.MediaPlayerEndReached -> VlcEvent.PlayerEndReached
            EventCodes.MediaPlayerEncounteredError -> VlcEvent.PlayerEncounteredError
            EventCodes.MediaPlayerTimeChanged -> VlcEvent.PlayerTimeChanged(longValue)
            EventCodes.MediaPlayerPositionChanged -> VlcEvent.PlayerPositionChanged(doubleValue.toFloat())
            EventCodes.MediaPlayerSeekableChanged -> VlcEvent.PlayerSeekableChanged(intValue != 0)
            EventCodes.MediaPlayerPausableChanged -> VlcEvent.PlayerPausableChanged(intValue != 0)
            EventCodes.MediaPlayerTitleChanged -> VlcEvent.PlayerTitleChanged(intValue)
            EventCodes.MediaPlayerSnapshotTaken -> VlcEvent.PlayerSnapshotTaken
            EventCodes.MediaPlayerLengthChanged -> VlcEvent.PlayerLengthChanged(longValue)
            EventCodes.MediaPlayerVout -> VlcEvent.PlayerVout(intValue)
            EventCodes.MediaPlayerESAdded ->
                VlcEvent.PlayerEsAdded(VlcTrackType.fromCode(trackType), intValue)

            EventCodes.MediaPlayerESDeleted ->
                VlcEvent.PlayerEsDeleted(VlcTrackType.fromCode(trackType), intValue)

            EventCodes.MediaPlayerESSelected ->
                VlcEvent.PlayerEsSelected(VlcTrackType.fromCode(trackType), intValue)

            EventCodes.MediaPlayerMuted -> VlcEvent.PlayerMuted(true)
            EventCodes.MediaPlayerUnmuted -> VlcEvent.PlayerMuted(false)
            EventCodes.MediaPlayerAudioVolume -> VlcEvent.PlayerAudioVolume(doubleValue.toInt())
            EventCodes.MediaPlayerChapterChanged -> VlcEvent.PlayerChapterChanged(intValue)
            else -> null
        }
        if (event != null) flow.tryEmit(event)
    }
}

private class JvmVideoCallbackAdapter(
    private val sink: VlcVideoSink,
) : JvmVideoCallback {
    private var cached: VlcVideoFrame? = null

    override fun onFrame(width: Int, height: Int, pitch: Int, pixels: ByteArray) {
        val previous = cached
        val frame = if (
            previous != null &&
            previous.width == width &&
            previous.height == height &&
            previous.pitch == pitch &&
            previous.pixels === pixels
        ) {
            previous
        } else {
            VlcVideoFrame(width, height, pitch, pixels).also { cached = it }
        }
        sink.onFrame(frame)
    }
}

actual object Vlc {
    private val isAndroid: Boolean by lazy {
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
    }

    actual val platform: String get() = if (isAndroid) "android" else "jvm"

    actual val version: String get() = VlcJni.nativeVersion()

    actual fun create(options: List<String>): VlcEngine = JvmVlcEngine(options)
}

private class JvmVlcEngine(options: List<String>) : VlcEngine {
    private val closed = AtomicBoolean(false)
    private val handle: Long = VlcJni.nativeCreateInstance(options.toTypedArray())
        .takeIf { it != 0L }
        ?: throw VlcException("libVLC could not be initialized: ${VlcJni.nativeLastError() ?: "unknown error"}")

    override val version: String get() = VlcJni.nativeVersion()
    override val capabilities: VlcCapabilities = VlcCapabilities.libVlc(Vlc.platform, version)

    fun nativeHandle(): Long {
        if (closed.get()) throw VlcException("VLC engine is closed")
        return handle
    }

    override fun createMedia(path: String, options: List<String>): VlcMedia {
        val handle = VlcJni.nativeCreateMedia(nativeHandle(), path).takeIf { it != 0L }
            ?: throw VlcException("libvlc_media_new_path failed: ${VlcJni.nativeLastError() ?: path}")
        return JvmVlcMedia(this, handle).also { media -> options.forEach(media::addOption) }
    }

    override fun createMediaFromLocation(location: String, options: List<String>): VlcMedia {
        val handle = VlcJni.nativeCreateMediaLocation(nativeHandle(), location).takeIf { it != 0L }
            ?: throw VlcException("libvlc_media_new_location failed: ${VlcJni.nativeLastError() ?: location}")
        return JvmVlcMedia(this, handle).also { media -> options.forEach(media::addOption) }
    }

    override fun createMediaPlayer(): VlcMediaPlayer = JvmVlcMediaPlayer(this)

    override fun createMediaList(): VlcMediaList = JvmVlcMediaList(this)

    override fun createMediaListPlayer(): VlcMediaListPlayer = JvmVlcMediaListPlayer(this)

    override fun lastError(): String? = VlcJni.nativeLastError()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VlcJni.nativeReleaseInstance(handle)
        }
    }
}

private class JvmVlcMedia(
    private val engine: JvmVlcEngine,
    private val handle: Long,
) : VlcMedia {
    private val closed = AtomicBoolean(false)

    // Media events are attached by the native backend on Android/Native. The
    // JVM bridge keeps the media handle scalar and exposes player events;
    // metadata is available synchronously through getMeta/tracks.
    override val events: Flow<VlcEvent> = emptyFlow()

    override val mrl: String? get() = VlcJni.nativeMediaMrl(requireHandle())
    override val state: VlcState get() = VlcState.fromCode(VlcJni.nativeMediaState(requireHandle()))
    override val duration: Long get() = VlcJni.nativeMediaDuration(requireHandle())
    override val parsedStatus: VlcParsedStatus
        get() = VlcParsedStatus.fromCode(VlcJni.nativeMediaParsedStatus(requireHandle()))

    override fun addOption(option: String) = VlcJni.nativeMediaAddOption(requireHandle(), option)

    override fun getMeta(meta: VlcMeta): String? = VlcJni.nativeMediaGetMeta(requireHandle(), meta.code)

    override fun setMeta(meta: VlcMeta, value: String): Boolean =
        VlcJni.nativeMediaSetMeta(requireHandle(), meta.code, value)

    override fun parse(timeoutMs: Long): Boolean = VlcJni.nativeMediaParse(requireHandle(), timeoutMs)

    override fun tracks(): List<VlcTrackInfo> {
        val flat = VlcJni.nativeMediaTracks(requireHandle())
        if (flat.size < 14) return emptyList()
        return (0 until flat.size / 14).map { index ->
            val offset = index * 14
            VlcTrackInfo(
                type = VlcTrackType.fromCode(flat[offset] as Int),
                id = flat[offset + 1] as Int,
                codec = flat[offset + 2] as Int,
                bitrate = flat[offset + 3] as Int,
                language = flat[offset + 4] as String?,
                description = flat[offset + 5] as String?,
                channels = flat[offset + 6] as Int,
                sampleRate = flat[offset + 7] as Int,
                width = flat[offset + 8] as Int,
                height = flat[offset + 9] as Int,
                sarNum = flat[offset + 10] as Int,
                sarDen = flat[offset + 11] as Int,
                frameRateNum = flat[offset + 12] as Int,
                frameRateDen = flat[offset + 13] as Int,
            )
        }
    }

    override fun retain(): VlcMedia {
        VlcJni.nativeRetainMedia(requireHandle())
        return JvmVlcMedia(engine, requireHandle())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VlcJni.nativeReleaseMedia(handle)
        }
    }

    fun requireHandle(): Long {
        if (closed.get()) throw VlcException("VLC media is closed")
        return handle
    }
}

private class JvmVlcMediaPlayer(private val engine: JvmVlcEngine) : VlcMediaPlayer {
    private val closed = AtomicBoolean(false)
    private val flow = MutableSharedFlow<VlcEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val callback = JvmEventCallback(flow)
    private val handle: Long = VlcJni.nativeCreatePlayer(engine.nativeHandle(), callback)
        .takeIf { it != 0L }
        ?: throw VlcException("libvlc_media_player_new failed: ${VlcJni.nativeLastError()}")
    private var mediaRef: VlcMedia? = null
    private var videoSink: JvmVideoCallbackAdapter? = null

    override val events: Flow<VlcEvent> = flow.asSharedFlow()

    override var media: VlcMedia?
        get() = mediaRef
        set(value) {
            when (value) {
                null -> VlcJni.nativePlayerSetMedia(requireHandle(), 0L)
                is JvmVlcMedia -> VlcJni.nativePlayerSetMedia(requireHandle(), value.requireHandle())
                else -> throw VlcException("Media belongs to a different backend")
            }
            mediaRef = value
        }

    override val state: VlcState get() = VlcState.fromCode(VlcJni.nativePlayerState(requireHandle()))
    override val isPlaying: Boolean get() = VlcJni.nativePlayerIsPlaying(requireHandle())

    override var time: Long
        get() = VlcJni.nativePlayerTime(requireHandle())
        set(value) = VlcJni.nativePlayerSetTime(requireHandle(), value)

    override var position: Float
        get() = VlcJni.nativePlayerPosition(requireHandle())
        set(value) = VlcJni.nativePlayerSetPosition(requireHandle(), value)

    override val duration: Long get() = VlcJni.nativePlayerLength(requireHandle())

    override var rate: Float
        get() = VlcJni.nativePlayerRate(requireHandle())
        set(value) = VlcJni.nativePlayerSetRate(requireHandle(), value)

    override var volume: Int
        get() = VlcJni.nativePlayerVolume(requireHandle())
        set(value) = VlcJni.nativePlayerSetVolume(requireHandle(), value)

    override var muted: Boolean
        get() = VlcJni.nativePlayerMute(requireHandle())
        set(value) = VlcJni.nativePlayerSetMute(requireHandle(), value)

    override var audioTrack: Int
        get() = VlcJni.nativePlayerAudioTrack(requireHandle())
        set(value) = VlcJni.nativePlayerSetAudioTrack(requireHandle(), value)

    override var videoTrack: Int
        get() = VlcJni.nativePlayerVideoTrack(requireHandle())
        set(value) = VlcJni.nativePlayerSetVideoTrack(requireHandle(), value)

    override var spuTrack: Int
        get() = VlcJni.nativePlayerSpuTrack(requireHandle())
        set(value) = VlcJni.nativePlayerSetSpuTrack(requireHandle(), value)

    override val audioTracks: List<VlcTrackDescription>
        get() = trackDescriptions(0)

    override val videoTracks: List<VlcTrackDescription>
        get() = trackDescriptions(1)

    override val spuTracks: List<VlcTrackDescription>
        get() = trackDescriptions(2)

    override val videoSize: VlcSize?
        get() = VlcJni.nativePlayerVideoSize(requireHandle())?.let {
            if (it.size >= 2) VlcSize(it[0].toInt(), it[1].toInt()) else null
        }

    override val hasVideoOutput: Boolean get() = VlcJni.nativePlayerHasVout(requireHandle())
    override val framesPerSecond: Float get() = VlcJni.nativePlayerFps(requireHandle())

    override var title: Int
        get() = VlcJni.nativePlayerTitle(requireHandle())
        set(value) = VlcJni.nativePlayerSetTitle(requireHandle(), value)

    override val titleCount: Int get() = VlcJni.nativePlayerTitleCount(requireHandle())

    override var chapter: Int
        get() = VlcJni.nativePlayerChapter(requireHandle())
        set(value) = VlcJni.nativePlayerSetChapter(requireHandle(), value)

    override val chapterCount: Int get() = VlcJni.nativePlayerChapterCount(requireHandle())

    override var audioDelay: Long
        get() = VlcJni.nativePlayerAudioDelay(requireHandle())
        set(value) = VlcJni.nativePlayerSetAudioDelay(requireHandle(), value)

    override var spuDelay: Long
        get() = VlcJni.nativePlayerSpuDelay(requireHandle())
        set(value) = VlcJni.nativePlayerSetSpuDelay(requireHandle(), value)

    override fun play() = VlcJni.nativePlayerPlay(requireHandle())

    override fun setPause(paused: Boolean) = VlcJni.nativePlayerSetPause(requireHandle(), paused)

    override fun stop() = VlcJni.nativePlayerStop(requireHandle())

    override fun setVideoSink(sink: VlcVideoSink): Boolean {
        val adapter = JvmVideoCallbackAdapter(sink)
        videoSink = adapter
        VlcJni.nativePlayerSetVideoSinkAuto(requireHandle(), adapter)
        return true
    }

    override fun setVideoSink(width: Int, height: Int, sink: VlcVideoSink): Boolean {
        require(width > 0 && height > 0) { "Video size must be positive" }
        val adapter = JvmVideoCallbackAdapter(sink)
        videoSink = adapter
        VlcJni.nativePlayerSetVideoSink(requireHandle(), width, height, adapter)
        return true
    }

    override fun clearVideoSink() {
        if (closed.get()) return
        VlcJni.nativePlayerClearVideoSink(requireHandle())
        videoSink = null
    }

    override fun setWindow(handle: Long, kind: VlcWindowKind): Boolean =
        VlcJni.nativePlayerSetWindow(requireHandle(), handle, kind.code)

    override fun snapshot(path: String, width: Int, height: Int): Boolean =
        VlcJni.nativePlayerSnapshot(requireHandle(), path, width, height)

    override fun addSlave(type: VlcTrackType, uri: String, select: Boolean): Boolean =
        VlcJni.nativePlayerAddSlave(requireHandle(), type.code, uri, select)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VlcJni.nativeReleasePlayer(handle)
            mediaRef = null
            videoSink = null
        }
    }

    fun requireHandle(): Long {
        if (closed.get()) throw VlcException("VLC media player is closed")
        return handle
    }

    private fun trackDescriptions(type: Int): List<VlcTrackDescription> {
        val flat = VlcJni.nativePlayerTrackDescriptions(requireHandle(), type)
        if (flat.size < 2) return emptyList()
        return (0 until flat.size / 2).map { index ->
            val offset = index * 2
            VlcTrackDescription(flat[offset] as Int, flat[offset + 1] as String? ?: "")
        }
    }
}

private class JvmVlcMediaList(private val engine: JvmVlcEngine) : VlcMediaList {
    private val closed = AtomicBoolean(false)
    private val handle: Long = VlcJni.nativeCreateMediaList(engine.nativeHandle())
        .takeIf { it != 0L } ?: throw VlcException("libvlc_media_list_new failed")

    override val count: Int get() = VlcJni.nativeMediaListCount(requireHandle())

    override fun add(media: VlcMedia) {
        val native = media as? JvmVlcMedia ?: throw VlcException("Media belongs to a different backend")
        VlcJni.nativeMediaListAdd(requireHandle(), native.requireHandle())
    }

    override fun insert(index: Int, media: VlcMedia) {
        val native = media as? JvmVlcMedia ?: throw VlcException("Media belongs to a different backend")
        VlcJni.nativeMediaListInsert(requireHandle(), index, native.requireHandle())
    }

    override fun remove(index: Int) = VlcJni.nativeMediaListRemove(requireHandle(), index)

    override fun mediaAt(index: Int): VlcMedia? =
        VlcJni.nativeMediaListItemAt(requireHandle(), index)
            .takeIf { it != 0L }
            ?.let { JvmVlcMedia(engine, it) }

    override fun clear() = VlcJni.nativeMediaListClear(requireHandle())

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VlcJni.nativeReleaseMediaList(handle)
        }
    }

    fun requireHandle(): Long {
        if (closed.get()) throw VlcException("VLC media list is closed")
        return handle
    }
}

private class JvmVlcMediaListPlayer(private val engine: JvmVlcEngine) : VlcMediaListPlayer {
    private val closed = AtomicBoolean(false)
    private val handle: Long = VlcJni.nativeCreateMediaListPlayer(engine.nativeHandle())
        .takeIf { it != 0L } ?: throw VlcException("libvlc_media_list_player_new failed")

    override var mediaList: VlcMediaList? = null
        set(value) {
            val native = value as? JvmVlcMediaList
            if (value != null && native == null) throw VlcException("Media list belongs to a different backend")
            VlcJni.nativeMediaListPlayerSetMediaList(requireHandle(), native?.requireHandle() ?: 0L)
            field = value
        }

    override var mediaPlayer: VlcMediaPlayer? = null
        set(value) {
            val native = value as? JvmVlcMediaPlayer
            if (value != null && native == null) throw VlcException("Media player belongs to a different backend")
            VlcJni.nativeMediaListPlayerSetMediaPlayer(requireHandle(), native?.requireHandle() ?: 0L)
            field = value
        }

    private var playbackModeValue: VlcPlaybackMode = VlcPlaybackMode.Default

    override var playbackMode: VlcPlaybackMode
        get() = playbackModeValue
        set(value) {
            VlcJni.nativeMediaListPlayerSetMode(requireHandle(), value.code)
            playbackModeValue = value
        }

    override fun play() = VlcJni.nativeMediaListPlayerPlay(requireHandle())
    override fun pause() = VlcJni.nativeMediaListPlayerPause(requireHandle())
    override fun stop() = VlcJni.nativeMediaListPlayerStop(requireHandle())
    override fun next() = VlcJni.nativeMediaListPlayerNext(requireHandle())
    override fun previous() = VlcJni.nativeMediaListPlayerPrevious(requireHandle())

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VlcJni.nativeReleaseMediaListPlayer(handle)
        }
    }

    private fun requireHandle(): Long {
        if (closed.get()) throw VlcException("VLC media list player is closed")
        return handle
    }
}
