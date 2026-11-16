/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

/** Mirrors `libvlc_state_t`. */
enum class VlcState(val code: Int) {
    NothingSpecial(0),
    Opening(1),
    Buffering(2),
    Playing(3),
    Paused(4),
    Stopped(5),
    Ended(6),
    Error(7),
    ;

    companion object {
        fun fromCode(code: Int): VlcState = entries.firstOrNull { it.code == code } ?: NothingSpecial
    }
}

/** Mirrors `libvlc_meta_t`; values are stable across VLC 3.x. */
enum class VlcMeta(val code: Int) {
    Title(0),
    Artist(1),
    Genre(2),
    Copyright(3),
    Album(4),
    TrackNumber(5),
    Description(6),
    Rating(7),
    Date(8),
    Setting(9),
    URL(10),
    Language(11),
    NowPlaying(12),
    Publisher(13),
    EncodedBy(14),
    ArtworkURL(15),
    TrackID(16),
    TrackTotal(17),
    Director(18),
    Season(19),
    Episode(20),
    ShowName(21),
    Actors(22),
    AlbumArtist(23),
    DiscNumber(24),
    DiscTotal(25),
    ;
}

/** Mirrors `libvlc_track_type_t`. */
enum class VlcTrackType(val code: Int) {
    Unknown(0),
    Audio(1),
    Video(2),
    Text(3),
    ;

    companion object {
        fun fromCode(code: Int): VlcTrackType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Mirrors `libvlc_media_parsed_status_t`. */
enum class VlcParsedStatus(val code: Int) {
    Unparsed(0),
    Skipped(1),
    Failed(2),
    Timeout(3),
    Done(4),
    ;

    companion object {
        fun fromCode(code: Int): VlcParsedStatus = entries.firstOrNull { it.code == code } ?: Unparsed
    }
}

/** Playback mode of [VlcMediaListPlayer]. */
enum class VlcPlaybackMode(val code: Int) {
    Default(0),
    Loop(1),
    Repeat(2),
}

/** Native window handle kind for video embedding. */
enum class VlcWindowKind(val code: Int) {
    XWindow(0),
    HWND(1),
    NSObject(2),
}

data class VlcSize(val width: Int, val height: Int)

/** A selectable track as exposed by libVLC's track description lists. */
data class VlcTrackDescription(val id: Int, val name: String)

/** Immutable track information obtained from `libvlc_media_tracks_get`. */
data class VlcTrackInfo(
    val type: VlcTrackType,
    val id: Int,
    val codec: Int,
    val bitrate: Int,
    val language: String?,
    val description: String?,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sarNum: Int = 0,
    val sarDen: Int = 0,
    val frameRateNum: Int = 0,
    val frameRateDen: Int = 0,
)

/** Pixel layout produced by [VlcMediaPlayer.setVideoSink]. */
enum class VlcPixelFormat {
    /** 32-bit BGRX, little-endian byte order, matching SDL_PIXELFORMAT_ARGB8888. */
    RV32,
}

/**
 * A decoded video frame. [pixels] is owned by the player and reused for the
 * next frame; copy it if the data must outlive the callback.
 */
class VlcVideoFrame(
    val width: Int,
    val height: Int,
    val pitch: Int,
    val pixels: ByteArray,
    val format: VlcPixelFormat = VlcPixelFormat.RV32,
)

/** Receives software-decoded frames. Called on a libVLC video thread. */
fun interface VlcVideoSink {
    fun onFrame(frame: VlcVideoFrame)
}

class VlcException(
    message: String,
    val code: Int = -1,
) : RuntimeException(message)

/**
 * Actual capabilities of the running backend. Native and JVM backends report
 * the full libVLC feature set; the browser backend reports the subset that
 * HTMLMediaElement can implement.
 */
data class VlcCapabilities(
    val platform: String,
    val libVlcVersion: String,
    val nativeLibVlc: Boolean,
    val videoOutput: Boolean,
    val audioOutput: Boolean,
    val softwareVideoCallbacks: Boolean,
    val hardwareDecoding: Boolean,
    val snapshot: Boolean,
    val equalizer: Boolean,
    val rendererDiscovery: Boolean,
    val mediaList: Boolean,
    val protocols: Set<String>,
    val notes: List<String> = emptyList(),
) {
    companion object {
        fun libVlc(platform: String, version: String): VlcCapabilities = VlcCapabilities(
            platform = platform,
            libVlcVersion = version,
            nativeLibVlc = true,
            videoOutput = true,
            audioOutput = true,
            softwareVideoCallbacks = true,
            hardwareDecoding = true,
            snapshot = true,
            equalizer = true,
            rendererDiscovery = true,
            mediaList = true,
            protocols = setOf(
                "file", "http", "https", "rtsp", "rtp", "udp", "hls", "dash",
                "ftp", "smb", "sftp", "mms", "livehttp", "mkv", "mp4",
            ),
        )

        fun browser(): VlcCapabilities = VlcCapabilities(
            platform = "wasm",
            libVlcVersion = "HTMLMediaElement",
            nativeLibVlc = false,
            videoOutput = true,
            audioOutput = true,
            softwareVideoCallbacks = false,
            hardwareDecoding = true,
            snapshot = false,
            equalizer = false,
            rendererDiscovery = false,
            mediaList = false,
            protocols = setOf("file", "http", "https", "hls", "dash"),
            notes = listOf(
                "Browser playback uses HTMLMediaElement, not libVLC.",
                "Protocol/codec support is whatever the browser implements.",
                "Advanced libVLC filters, renderers and snapshots are unavailable.",
            ),
        )
    }
}
