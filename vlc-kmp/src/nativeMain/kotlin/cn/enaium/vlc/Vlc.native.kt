/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package cn.enaium.vlc

import cn.enaium.vlc.cinterop.*
import cnames.structs.libvlc_event_manager_t
import cnames.structs.libvlc_instance_t
import cnames.structs.libvlc_media_list_player_t
import cnames.structs.libvlc_media_list_t
import cnames.structs.libvlc_media_player_t
import cnames.structs.libvlc_media_t
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.*
import kotlin.native.Platform
import platform.posix.memcpy
import kotlin.time.Duration.Companion.milliseconds

private const val EVENT_BUFFER = 64

/** Numeric libvlc_event_e values; kept local to avoid cinterop enum plumbing. */
private object EventCode {
    const val MediaMetaChanged = 0
    const val MediaSubItemAdded = 1
    const val MediaDurationChanged = 2
    const val MediaParsedChanged = 3
    const val MediaStateChanged = 5

    const val PlayerMediaChanged = 0x100
    const val PlayerNothingSpecial = 0x101
    const val PlayerOpening = 0x102
    const val PlayerBuffering = 0x103
    const val PlayerPlaying = 0x104
    const val PlayerPaused = 0x105
    const val PlayerStopped = 0x106
    const val PlayerEndReached = 0x109
    const val PlayerEncounteredError = 0x10a
    const val PlayerTimeChanged = 0x10b
    const val PlayerPositionChanged = 0x10c
    const val PlayerSeekableChanged = 0x10d
    const val PlayerPausableChanged = 0x10e
    const val PlayerTitleChanged = 0x10f
    const val PlayerSnapshotTaken = 0x110
    const val PlayerLengthChanged = 0x111
    const val PlayerVout = 0x112
    const val PlayerEsAdded = 0x114
    const val PlayerEsDeleted = 0x115
    const val PlayerEsSelected = 0x116
    const val PlayerMuted = 0x119
    const val PlayerUnmuted = 0x11a
    const val PlayerAudioVolume = 0x11b
    const val PlayerChapterChanged = 0x11d
}

private fun mutableEventFlow(): MutableSharedFlow<VlcEvent> =
    MutableSharedFlow(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

private typealias NativeEventCallback =
    (type: Int, longValue: Long, doubleValue: Double, intValue: Int, trackType: Int) -> Unit

/**
 * Event callbacks may outlive the Kotlin object that registered them for a few
 * instructions. The registry is copy-on-write so dispatch never observes a
 * half-mutated map.
 */
private object NativeEventRegistry {
    private val nextId = atomic(1L)
    private val entries = atomic<Map<Long, NativeEventCallback>>(emptyMap())

    fun register(callback: NativeEventCallback): Long {
        val id = nextId.getAndIncrement()
        entries.update { it + (id to callback) }
        return id
    }

    fun unregister(id: Long) {
        entries.update { it - id }
    }

    fun dispatch(id: Long, type: Int, longValue: Long, doubleValue: Double, intValue: Int, trackType: Int) {
        entries.value[id]?.invoke(type, longValue, doubleValue, intValue, trackType)
    }
}

private val nativeEventCallback = staticCFunction {
        event: CPointer<libvlc_event_t>?, opaque: COpaquePointer? ->
    try {
        if (event == null || opaque == null) return@staticCFunction
        val type = vlc_kmp_event_type(event)
        NativeEventRegistry.dispatch(
            id = opaque.toLong(),
            type = type,
            longValue = when (type) {
                EventCode.PlayerTimeChanged -> vlc_kmp_event_time(event)
                EventCode.PlayerLengthChanged -> vlc_kmp_event_length(event)
                EventCode.MediaDurationChanged -> vlc_kmp_event_duration(event)
                else -> 0L
            },
            doubleValue = when (type) {
                EventCode.PlayerPositionChanged -> vlc_kmp_event_position(event).toDouble()
                EventCode.PlayerBuffering -> vlc_kmp_event_buffering(event).toDouble()
                EventCode.PlayerAudioVolume -> vlc_kmp_event_volume(event).toDouble()
                else -> 0.0
            },
            intValue = when (type) {
                EventCode.MediaMetaChanged -> vlc_kmp_event_meta(event)
                EventCode.MediaStateChanged -> vlc_kmp_event_state(event)
                EventCode.MediaParsedChanged -> vlc_kmp_event_parsed_status(event)
                else -> vlc_kmp_event_int(event)
            },
            trackType = vlc_kmp_event_track_type(event),
        )
    } catch (_: Throwable) {
        // Exceptions must never cross a C callback boundary.
    }
}

private fun attachPlayerEvents(manager: CPointer<libvlc_event_manager_t>?, id: Long) {
    vlc_kmp_attach_player_events(manager, nativeEventCallback, id.toCPointer<ByteVar>())
}

private fun detachPlayerEvents(manager: CPointer<libvlc_event_manager_t>?, id: Long) {
    vlc_kmp_detach_player_events(manager, nativeEventCallback, id.toCPointer<ByteVar>())
}

private fun attachMediaEvents(manager: CPointer<libvlc_event_manager_t>?, id: Long) {
    vlc_kmp_attach_media_events(manager, nativeEventCallback, id.toCPointer<ByteVar>())
}

private fun detachMediaEvents(manager: CPointer<libvlc_event_manager_t>?, id: Long) {
    vlc_kmp_detach_media_events(manager, nativeEventCallback, id.toCPointer<ByteVar>())
}

private fun mediaManager(media: CPointer<libvlc_media_t>?): CPointer<libvlc_event_manager_t>? =
    media?.let { libvlc_media_event_manager(it) }

private fun playerManager(player: CPointer<libvlc_media_player_t>?): CPointer<libvlc_event_manager_t>? =
    player?.let { libvlc_media_player_event_manager(it) }

// ---------------------------------------------------------------------------
// Video callbacks
// ---------------------------------------------------------------------------

private val videoNextId = atomic(1L)
private val videoEntries = atomic<Map<Long, NativeVideoContext>>(emptyMap())
// Kept until the context is disposed: an in-flight lock callback must always
// find its context to release the mutex, even after the sink was detached.
private val videoUnlockEntries = atomic<Map<Long, NativeVideoContext>>(emptyMap())

private class NativeVideoContext(
    val sink: VlcVideoSink,
) {
    val lock = SynchronizedObject()
    var active = true
    var width = 0
        private set
    var height = 0
        private set
    var pitch = 0
        private set
    var buffer: CPointer<ByteVar>? = null
        private set
    var pixels = ByteArray(0)
        private set
    private val retiredBuffers = mutableListOf<CPointer<ByteVar>>()
    val id: Long = videoNextId.getAndIncrement()

    fun resize(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0)
        lock.lock()
        return try {
            if (!active) {
                false
            } else if (this.width == width && this.height == height && buffer != null) {
                true
            } else {
                val nextPitch = width * 4
                val size = nextPitch * height
                // The format callback can run on the same video thread that
                // still holds the lock for an in-flight picture. Never free
                // the previous buffer here; release it after player teardown.
                buffer?.let { retiredBuffers += it }
                buffer = nativeHeap.allocArray(size)
                pixels = ByteArray(size)
                this.width = width
                this.height = height
                this.pitch = nextPitch
                true
            }
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.lock()
        try {
            active = false
            videoEntries.update { it - id }
        } finally {
            lock.unlock()
        }
    }

    fun dispose() {
        lock.lock()
        try {
            videoUnlockEntries.update { it - id }
            buffer?.let { nativeHeap.free(it) }
            buffer = null
            retiredBuffers.forEach { nativeHeap.free(it) }
            retiredBuffers.clear()
        } finally {
            lock.unlock()
        }
    }
}

private val videoLock: libvlc_video_lock_cb = staticCFunction {
        opaque: COpaquePointer?, planes: CPointer<COpaquePointerVar>? ->
    try {
        val context = opaque?.toLong()?.let { videoEntries.value[it] } ?: return@staticCFunction null
        context.lock.lock()
        val buffer = context.buffer
        if (!context.active || buffer == null) {
            context.lock.unlock()
            return@staticCFunction null
        }
        if (planes != null) planes[0] = buffer
        buffer
    } catch (_: Throwable) {
        null
    }
}

private val videoUnlock: libvlc_video_unlock_cb = staticCFunction {
        opaque: COpaquePointer?, _: COpaquePointer?, _: CPointer<COpaquePointerVar>? ->
    try {
        opaque?.toLong()?.let { videoUnlockEntries.value[it] }?.lock?.unlock()
    } catch (_: Throwable) {
    }
}

private val videoFormatSetup: libvlc_video_format_cb = staticCFunction {
        opaque: CPointer<COpaquePointerVar>?, chroma: CPointer<ByteVar>?,
        width: CPointer<UIntVar>?, height: CPointer<UIntVar>?,
        pitches: CPointer<UIntVar>?, lines: CPointer<UIntVar>? ->
    try {
        if (chroma != null) {
            chroma[0] = 'R'.code.toByte()
            chroma[1] = 'V'.code.toByte()
            chroma[2] = '3'.code.toByte()
            chroma[3] = '2'.code.toByte()
        }
        val context = opaque?.get(0)?.toLong()?.let { videoEntries.value[it] }
            ?: return@staticCFunction 0u
        val pixelWidth = width?.get(0)?.toInt() ?: return@staticCFunction 0u
        val pixelHeight = height?.get(0)?.toInt() ?: return@staticCFunction 0u
        if (pixelWidth <= 0 || pixelHeight <= 0) return@staticCFunction 0u
        if (!context.resize(pixelWidth, pixelHeight)) return@staticCFunction 0u
        if (pitches != null) pitches[0] = (pixelWidth * 4).toUInt()
        if (lines != null) lines[0] = pixelHeight.toUInt()
        1u
    } catch (_: Throwable) {
        0u
    }
}

private val videoFormatCleanup: libvlc_video_cleanup_cb = staticCFunction { _: COpaquePointer? ->
}

private val videoDisplay: libvlc_video_display_cb = staticCFunction { opaque: COpaquePointer?, _: COpaquePointer? ->
    try {
        val context = opaque?.toLong()?.let { videoEntries.value[it] } ?: return@staticCFunction
        context.lock.lock()
        try {
            if (!context.active) return@staticCFunction
            val buffer = context.buffer ?: return@staticCFunction
            memcpy(context.pixels.usePinned { it.addressOf(0) }, buffer, context.pixels.size.convert())
            context.sink.onFrame(
                VlcVideoFrame(
                    width = context.width,
                    height = context.height,
                    pitch = context.pitch,
                    pixels = context.pixels,
                ),
            )
        } finally {
            context.lock.unlock()
        }
    } catch (_: Throwable) {
    }
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

private fun nativePlatformName(): String {
    val os = when (Platform.osFamily.name) {
        "MACOSX" -> "macos"
        "LINUX" -> "linux"
        "WINDOWS" -> "mingw"
        "ANDROID" -> "androidNative"
        else -> Platform.osFamily.name.lowercase()
    }
    return os + Platform.cpuArchitecture.name.lowercase().replaceFirstChar { it.uppercase() }
}

private fun ensurePluginPath() {
    // VLC.app looks for plugins relative to the bundle; the C shim sets
    // VLC_PLUGIN_PATH on macOS when the library was loaded from outside it.
    vlc_kmp_ensure_plugin_path()
}

actual object Vlc {
    actual val platform: String get() = nativePlatformName()

    actual val version: String
        get() = libvlc_get_version()?.toKString() ?: "unknown"

    actual fun create(options: List<String>): VlcEngine {
        ensurePluginPath()
        return NativeVlcEngine(options)
    }
}

private class NativeVlcEngine(options: List<String>) : VlcEngine {
    private val closed = atomic(false)

    val instance: CPointer<libvlc_instance_t> = memScoped {
        val count = options.size
        val argv = allocArray<CPointerVar<ByteVar>>(count)
        options.forEachIndexed { index, option -> argv[index] = option.cstr.ptr }
        libvlc_new(count, argv)
            ?: throw VlcException("libvlc_new failed: ${libvlc_errmsg()?.toKString() ?: "unknown error"}")
    }

    override val version: String = Vlc.version
    override val capabilities: VlcCapabilities = VlcCapabilities.libVlc(Vlc.platform, version)

    override fun createMedia(path: String, options: List<String>): VlcMedia {
        requireOpen()
        val handle = memScoped {
            libvlc_media_new_path(instance, path.cstr)
                ?: throw VlcException("libvlc_media_new_path failed: ${lastError() ?: path}")
        }
        return NativeVlcMedia(this, handle).also { media -> options.forEach(media::addOption) }
    }

    override fun createMediaFromLocation(location: String, options: List<String>): VlcMedia {
        requireOpen()
        val handle = memScoped {
            libvlc_media_new_location(instance, location.cstr)
                ?: throw VlcException("libvlc_media_new_location failed: ${lastError() ?: location}")
        }
        return NativeVlcMedia(this, handle).also { media -> options.forEach(media::addOption) }
    }

    override fun createMediaPlayer(): VlcMediaPlayer {
        requireOpen()
        return NativeVlcMediaPlayer(this)
    }

    override fun createMediaList(): VlcMediaList {
        requireOpen()
        return NativeVlcMediaList(this)
    }

    override fun createMediaListPlayer(): VlcMediaListPlayer {
        requireOpen()
        return NativeVlcMediaListPlayer(this)
    }

    override fun lastError(): String? = libvlc_errmsg()?.toKString()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            libvlc_release(instance)
        }
    }

    fun retain() {
        requireOpen()
        libvlc_retain(instance)
    }

    fun requireOpen() {
        if (closed.value) throw VlcException("VLC engine is closed")
    }
}

// ---------------------------------------------------------------------------
// Media
// ---------------------------------------------------------------------------

private class NativeVlcMedia(
    private val engine: NativeVlcEngine,
    private val handle: CPointer<libvlc_media_t>,
) : VlcMedia {
    private val closed = atomic(false)
    private val flow = mutableEventFlow()
    private val eventId = NativeEventRegistry.register { type, longValue, _, intValue, _ ->
        val event = when (type) {
            EventCode.MediaMetaChanged -> VlcEvent.MediaMetaChanged(VlcMeta.entries.getOrNull(intValue) ?: VlcMeta.Title)
            EventCode.MediaDurationChanged -> VlcEvent.MediaDurationChanged(longValue)
            EventCode.MediaParsedChanged -> VlcEvent.MediaParsedChanged(VlcParsedStatus.fromCode(intValue))
            EventCode.MediaStateChanged -> VlcEvent.MediaStateChanged(VlcState.fromCode(intValue))
            EventCode.MediaSubItemAdded -> VlcEvent.MediaSubItemAdded(null)
            else -> null
        }
        if (event != null) flow.tryEmit(event)
    }

    init {
        engine.retain()
        attachMediaEvents(mediaManager(handle), eventId)
    }

    override val events: Flow<VlcEvent> = flow.asSharedFlow()

    override val mrl: String? get() = withString(libvlc_media_get_mrl(requireHandle()))
    override val state: VlcState get() = VlcState.fromCode(libvlc_media_get_state(requireHandle()).toInt())
    override val duration: Long get() = libvlc_media_get_duration(requireHandle())
    override val parsedStatus: VlcParsedStatus
        get() = VlcParsedStatus.fromCode(libvlc_media_get_parsed_status(requireHandle()).toInt())

    override fun addOption(option: String) {
        memScoped { libvlc_media_add_option(requireHandle(), option.cstr) }
    }

    override fun getMeta(meta: VlcMeta): String? =
        withString(libvlc_media_get_meta(requireHandle(), libvlc_meta_t.byValue(meta.code.toUInt())))

    override fun setMeta(meta: VlcMeta, value: String): Boolean = memScoped {
        libvlc_media_set_meta(requireHandle(), libvlc_meta_t.byValue(meta.code.toUInt()), value.cstr)
        true
    }

    override fun parse(timeoutMs: Long): Boolean {
        val started = memScoped {
            libvlc_media_parse_with_options(requireHandle(), libvlc_media_parse_local, timeoutMs.toInt())
        }
        if (started != 0) return false
        val deadline = if (timeoutMs > 0) {
            kotlin.time.TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
        } else {
            null
        }
        while (parsedStatus == VlcParsedStatus.Unparsed) {
            if (deadline != null && deadline.hasPassedNow()) return false
            runBlocking { delay(10) }
        }
        return parsedStatus != VlcParsedStatus.Failed && parsedStatus != VlcParsedStatus.Timeout
    }

    override fun tracks(): List<VlcTrackInfo> = memScoped {
        val tracksPtr = alloc<CPointerVar<CPointerVar<libvlc_media_track_t>>>()
        val count = libvlc_media_tracks_get(requireHandle(), tracksPtr.ptr)
        if (count == 0u) return@memScoped emptyList()
        val tracks = tracksPtr.value
        try {
            (0 until count.toInt()).mapNotNull { index ->
                val track = tracks?.get(index) ?: return@mapNotNull null
                val out = alloc<vlc_kmp_track>()
                vlc_kmp_read_track(track, out.ptr)
                VlcTrackInfo(
                    type = VlcTrackType.fromCode(out.type),
                    id = out.id,
                    codec = out.codec.toInt(),
                    bitrate = out.bitrate,
                    language = out.language?.toKString(),
                    description = out.description?.toKString(),
                    channels = out.channels.toInt(),
                    sampleRate = out.sample_rate.toInt(),
                    width = out.width.toInt(),
                    height = out.height.toInt(),
                    sarNum = out.sar_num.toInt(),
                    sarDen = out.sar_den.toInt(),
                    frameRateNum = out.frame_rate_num.toInt(),
                    frameRateDen = out.frame_rate_den.toInt(),
                )
            }
        } finally {
            libvlc_media_tracks_release(tracks, count)
        }
    }

    override fun retain(): VlcMedia {
        libvlc_media_retain(requireHandle())
        return NativeVlcMedia(engine, handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detachMediaEvents(mediaManager(handle), eventId)
            NativeEventRegistry.unregister(eventId)
            libvlc_media_release(handle)
            libvlc_release(engine.instance)
        }
    }

    fun requireHandle(): CPointer<libvlc_media_t> {
        if (closed.value) throw VlcException("VLC media is closed")
        return handle
    }

    private fun withString(pointer: CPointer<ByteVar>?): String? {
        if (pointer == null) return null
        return try {
            pointer.toKString()
        } finally {
            libvlc_free(pointer)
        }
    }
}

// ---------------------------------------------------------------------------
// Media player
// ---------------------------------------------------------------------------

private class NativeVlcMediaPlayer(private val engine: NativeVlcEngine) : VlcMediaPlayer {
    private val closed = atomic(false)
    private val flow = mutableEventFlow()
    private val handle: CPointer<libvlc_media_player_t> = libvlc_media_player_new(engine.instance)
        ?: throw VlcException("libvlc_media_player_new failed: ${engine.lastError() ?: "unknown error"}")
    private var mediaRef: VlcMedia? = null
    private var videoContext: NativeVideoContext? = null
    private var videoUsesFormatCallbacks = false
    private val retiredVideoContexts = mutableListOf<NativeVideoContext>()
    private val eventId = NativeEventRegistry.register { type, longValue, doubleValue, intValue, trackType ->
        val event = when (type) {
            EventCode.PlayerMediaChanged -> VlcEvent.PlayerMediaChanged(null)
            EventCode.PlayerNothingSpecial -> VlcEvent.PlayerNothingSpecial
            EventCode.PlayerOpening -> VlcEvent.PlayerOpening
            EventCode.PlayerBuffering -> VlcEvent.PlayerBuffering(doubleValue.toFloat())
            EventCode.PlayerPlaying -> VlcEvent.PlayerPlaying
            EventCode.PlayerPaused -> VlcEvent.PlayerPaused
            EventCode.PlayerStopped -> VlcEvent.PlayerStopped
            EventCode.PlayerEndReached -> VlcEvent.PlayerEndReached
            EventCode.PlayerEncounteredError -> VlcEvent.PlayerEncounteredError
            EventCode.PlayerTimeChanged -> VlcEvent.PlayerTimeChanged(longValue)
            EventCode.PlayerPositionChanged -> VlcEvent.PlayerPositionChanged(doubleValue.toFloat())
            EventCode.PlayerSeekableChanged -> VlcEvent.PlayerSeekableChanged(intValue != 0)
            EventCode.PlayerPausableChanged -> VlcEvent.PlayerPausableChanged(intValue != 0)
            EventCode.PlayerTitleChanged -> VlcEvent.PlayerTitleChanged(intValue)
            EventCode.PlayerChapterChanged -> VlcEvent.PlayerChapterChanged(intValue)
            EventCode.PlayerLengthChanged -> VlcEvent.PlayerLengthChanged(longValue)
            EventCode.PlayerVout -> VlcEvent.PlayerVout(intValue)
            EventCode.PlayerAudioVolume -> VlcEvent.PlayerAudioVolume(doubleValue.toInt())
            EventCode.PlayerMuted -> VlcEvent.PlayerMuted(true)
            EventCode.PlayerUnmuted -> VlcEvent.PlayerMuted(false)
            EventCode.PlayerEsAdded -> VlcEvent.PlayerEsAdded(VlcTrackType.fromCode(trackType), intValue)
            EventCode.PlayerEsDeleted -> VlcEvent.PlayerEsDeleted(VlcTrackType.fromCode(trackType), intValue)
            EventCode.PlayerEsSelected -> VlcEvent.PlayerEsSelected(VlcTrackType.fromCode(trackType), intValue)
            EventCode.PlayerSnapshotTaken -> VlcEvent.PlayerSnapshotTaken
            else -> null
        }
        if (event != null) flow.tryEmit(event)
    }

    init {
        engine.retain()
        attachPlayerEvents(playerManager(handle), eventId)
    }

    override val events: Flow<VlcEvent> = flow.asSharedFlow()

    override var media: VlcMedia?
        get() = mediaRef
        set(value) {
            requireHandle()
            when (value) {
                null -> libvlc_media_player_set_media(handle, null)
                is NativeVlcMedia -> libvlc_media_player_set_media(handle, value.requireHandle())
                else -> throw VlcException("Media belongs to a different backend")
            }
            mediaRef = value
        }

    override val state: VlcState get() = VlcState.fromCode(libvlc_media_player_get_state(requireHandle()).toInt())
    override val isPlaying: Boolean get() = libvlc_media_player_is_playing(requireHandle()) != 0

    override var time: Long
        get() = libvlc_media_player_get_time(requireHandle())
        set(value) = libvlc_media_player_set_time(requireHandle(), value)

    override var position: Float
        get() = libvlc_media_player_get_position(requireHandle())
        set(value) = libvlc_media_player_set_position(requireHandle(), value)

    override val duration: Long get() = libvlc_media_player_get_length(requireHandle())

    override var rate: Float
        get() = libvlc_media_player_get_rate(requireHandle())
        set(value) {
            libvlc_media_player_set_rate(requireHandle(), value)
        }

    override var volume: Int
        get() = libvlc_audio_get_volume(requireHandle())
        set(value) {
            libvlc_audio_set_volume(requireHandle(), value)
        }

    override var muted: Boolean
        get() = libvlc_audio_get_mute(requireHandle()) != 0
        set(value) = libvlc_audio_set_mute(requireHandle(), if (value) 1 else 0)

    override var audioTrack: Int
        get() = libvlc_audio_get_track(requireHandle())
        set(value) {
            libvlc_audio_set_track(requireHandle(), value)
        }

    override var videoTrack: Int
        get() = libvlc_video_get_track(requireHandle())
        set(value) {
            libvlc_video_set_track(requireHandle(), value)
        }

    override var spuTrack: Int
        get() = libvlc_video_get_spu(requireHandle())
        set(value) {
            libvlc_video_set_spu(requireHandle(), value)
        }

    override val audioTracks: List<VlcTrackDescription>
        get() = trackDescriptions(libvlc_audio_get_track_description(handle))

    override val videoTracks: List<VlcTrackDescription>
        get() = trackDescriptions(libvlc_video_get_track_description(handle))

    override val spuTracks: List<VlcTrackDescription>
        get() = trackDescriptions(libvlc_video_get_spu_description(handle))

    override val videoSize: VlcSize?
        get() = memScoped {
            val width = alloc<UIntVar>()
            val height = alloc<UIntVar>()
            if (libvlc_video_get_size(handle, 0u, width.ptr, height.ptr) == 0) {
                VlcSize(width.value.toInt(), height.value.toInt())
            } else {
                null
            }
        }

    override val hasVideoOutput: Boolean get() = libvlc_media_player_has_vout(handle) > 0u
    override val framesPerSecond: Float get() = libvlc_media_player_get_fps(handle)

    override var title: Int
        get() = libvlc_media_player_get_title(handle)
        set(value) = libvlc_media_player_set_title(handle, value)

    override val titleCount: Int get() = libvlc_media_player_get_title_count(handle)

    override var chapter: Int
        get() = libvlc_media_player_get_chapter(handle)
        set(value) = libvlc_media_player_set_chapter(handle, value)

    override val chapterCount: Int get() = libvlc_media_player_get_chapter_count(handle)

    override var audioDelay: Long
        get() = libvlc_audio_get_delay(handle)
        set(value) {
            libvlc_audio_set_delay(handle, value)
        }

    override var spuDelay: Long
        get() = libvlc_video_get_spu_delay(handle)
        set(value) {
            libvlc_video_set_spu_delay(handle, value)
        }

    override fun play() {
        libvlc_media_player_play(requireHandle())
    }

    override fun setPause(paused: Boolean) {
        libvlc_media_player_set_pause(requireHandle(), if (paused) 1 else 0)
    }

    override fun stop() {
        libvlc_media_player_stop(requireHandle())
    }

    override fun setVideoSink(sink: VlcVideoSink): Boolean {
        clearVideoSink()
        val context = NativeVideoContext(sink)
        videoEntries.update { it + (context.id to context) }
        videoUnlockEntries.update { it + (context.id to context) }
        videoContext = context
        memScoped {
            libvlc_video_set_callbacks(handle, videoLock, videoUnlock, videoDisplay, context.id.toCPointer<ByteVar>())
            libvlc_video_set_format_callbacks(handle, videoFormatSetup, videoFormatCleanup)
        }
        videoUsesFormatCallbacks = true
        return true
    }

    override fun setVideoSink(width: Int, height: Int, sink: VlcVideoSink): Boolean {
        require(width > 0 && height > 0) { "Video size must be positive" }
        clearVideoSink()
        val context = NativeVideoContext(sink).also { it.resize(width, height) }
        videoEntries.update { it + (context.id to context) }
        videoUnlockEntries.update { it + (context.id to context) }
        videoContext = context
        memScoped {
            libvlc_video_set_callbacks(handle, videoLock, videoUnlock, videoDisplay, context.id.toCPointer<ByteVar>())
            libvlc_video_set_format(handle, "RV32".cstr, width.toUInt(), height.toUInt(), (width * 4).toUInt())
        }
        videoUsesFormatCallbacks = false
        return true
    }

    override fun clearVideoSink() {
        if (videoContext != null) {
            libvlc_video_set_callbacks(handle, null, null, null, null)
            if (videoUsesFormatCallbacks) {
                libvlc_video_set_format_callbacks(handle, null, null)
            }
        }
        videoUsesFormatCallbacks = false
        videoContext?.let { context ->
            context.close()
            retiredVideoContexts += context
        }
        videoContext = null
    }

    override fun setWindow(handle: Long, kind: VlcWindowKind): Boolean {
        return when (kind) {
            VlcWindowKind.XWindow -> {
                if (!Platform.osFamily.name.equals("LINUX", ignoreCase = true)) return false
                libvlc_media_player_set_xwindow(requireHandle(), handle.toUInt())
                true
            }

            VlcWindowKind.HWND -> {
                if (!Platform.osFamily.name.equals("WINDOWS", ignoreCase = true)) return false
                libvlc_media_player_set_hwnd(requireHandle(), handle.toCPointer<ByteVar>())
                true
            }

            VlcWindowKind.NSObject -> {
                if (!Platform.osFamily.name.equals("MACOSX", ignoreCase = true)) return false
                libvlc_media_player_set_nsobject(requireHandle(), handle.toCPointer<ByteVar>())
                true
            }
        }
    }

    override fun snapshot(path: String, width: Int, height: Int): Boolean = memScoped {
        libvlc_video_take_snapshot(requireHandle(), 0u, path.cstr, width.toUInt(), height.toUInt()) == 0
    }

    override fun addSlave(type: VlcTrackType, uri: String, select: Boolean): Boolean = memScoped {
        val slaveType = when (type) {
            VlcTrackType.Text -> libvlc_media_slave_type_t.libvlc_media_slave_type_subtitle
            VlcTrackType.Audio -> libvlc_media_slave_type_t.libvlc_media_slave_type_audio
            else -> return false
        }
        libvlc_media_player_add_slave(requireHandle(), slaveType, uri.cstr, select) == 0
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            clearVideoSink()
            mediaRef = null
            detachPlayerEvents(playerManager(handle), eventId)
            NativeEventRegistry.unregister(eventId)
            libvlc_media_player_release(handle)
            retiredVideoContexts.forEach { it.dispose() }
            retiredVideoContexts.clear()
            libvlc_release(engine.instance)
        }
    }

    fun nativeHandle(): CPointer<libvlc_media_player_t> = requireHandle()

    private fun requireHandle(): CPointer<libvlc_media_player_t> {
        if (closed.value) throw VlcException("VLC media player is closed")
        return handle
    }

    private fun trackDescriptions(head: CPointer<libvlc_track_description_t>?): List<VlcTrackDescription> {
        if (head == null) return emptyList()
        return try {
            val result = mutableListOf<VlcTrackDescription>()
            var current: CPointer<libvlc_track_description_t>? = head
            while (current != null) {
                val value = current.pointed
                result += VlcTrackDescription(value.i_id, value.psz_name?.toKString().orEmpty())
                current = value.p_next
            }
            result
        } finally {
            libvlc_track_description_list_release(head)
        }
    }
}

// ---------------------------------------------------------------------------
// Media list / list player
// ---------------------------------------------------------------------------

private class NativeVlcMediaList(private val engine: NativeVlcEngine) : VlcMediaList {
    private val closed = atomic(false)
    private val handle: CPointer<libvlc_media_list_t> = libvlc_media_list_new(engine.instance)
        ?: throw VlcException("libvlc_media_list_new failed")

    init {
        engine.retain()
    }

    override val count: Int get() = locked { libvlc_media_list_count(handle) }

    override fun add(media: VlcMedia) {
        val native = media as? NativeVlcMedia ?: throw VlcException("Media belongs to a different backend")
        locked { libvlc_media_list_add_media(handle, native.requireHandle()) }
    }

    override fun insert(index: Int, media: VlcMedia) {
        val native = media as? NativeVlcMedia ?: throw VlcException("Media belongs to a different backend")
        locked { libvlc_media_list_insert_media(handle, native.requireHandle(), index) }
    }

    override fun remove(index: Int) {
        locked { libvlc_media_list_remove_index(handle, index) }
    }

    override fun mediaAt(index: Int): VlcMedia? {
        val media = locked { libvlc_media_list_item_at_index(handle, index) } ?: return null
        return NativeVlcMedia(engine, media)
    }

    override fun clear() {
        locked {
            var index = libvlc_media_list_count(handle) - 1
            while (index >= 0) {
                libvlc_media_list_remove_index(handle, index)
                index--
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            libvlc_media_list_release(handle)
            libvlc_release(engine.instance)
        }
    }

    fun nativeHandle(): CPointer<libvlc_media_list_t> = handle

    private inline fun <T> locked(block: () -> T): T {
        libvlc_media_list_lock(handle)
        return try {
            block()
        } finally {
            libvlc_media_list_unlock(handle)
        }
    }
}

private class NativeVlcMediaListPlayer(private val engine: NativeVlcEngine) : VlcMediaListPlayer {
    private val closed = atomic(false)
    private val handle: CPointer<libvlc_media_list_player_t> = libvlc_media_list_player_new(engine.instance)
        ?: throw VlcException("libvlc_media_list_player_new failed")

    override var mediaList: VlcMediaList? = null
        set(value) {
            when (value) {
                null -> libvlc_media_list_player_set_media_list(handle, null)
                is NativeVlcMediaList -> libvlc_media_list_player_set_media_list(handle, value.nativeHandle())
                else -> throw VlcException("Media list belongs to a different backend")
            }
            field = value
        }

    override var mediaPlayer: VlcMediaPlayer? = null
        set(value) {
            when (value) {
                null -> libvlc_media_list_player_set_media_player(handle, null)
                is NativeVlcMediaPlayer -> libvlc_media_list_player_set_media_player(handle, value.nativeHandle())
                else -> throw VlcException("Media player belongs to a different backend")
            }
            field = value
        }

    private var playbackModeValue: VlcPlaybackMode = VlcPlaybackMode.Default

    override var playbackMode: VlcPlaybackMode
        get() = playbackModeValue
        set(value) {
            libvlc_media_list_player_set_playback_mode(handle, libvlc_playback_mode_t.byValue(value.code.toUInt()))
            playbackModeValue = value
        }

    init {
        engine.retain()
    }

    override fun play() = libvlc_media_list_player_play(handle)
    override fun pause() = libvlc_media_list_player_pause(handle)
    override fun stop() = libvlc_media_list_player_stop(handle)
    override fun next() {
        libvlc_media_list_player_next(handle)
    }

    override fun previous() {
        libvlc_media_list_player_previous(handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            libvlc_media_list_player_release(handle)
            libvlc_release(engine.instance)
        }
    }
}
