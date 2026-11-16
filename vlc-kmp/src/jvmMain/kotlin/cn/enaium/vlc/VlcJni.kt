/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

/** Receives event payloads from the JNI bridge on a libVLC thread. */
internal interface VlcEventCallback {
    fun onEvent(
        type: Int,
        longValue: Long,
        doubleValue: Double,
        intValue: Int,
        trackType: Int,
    )
}

/** Receives software video frames; [pixels] is reused across calls. */
internal interface JvmVideoCallback {
    fun onFrame(width: Int, height: Int, pitch: Int, pixels: ByteArray)
}

/** JNI entry points on `cn.enaium.vlc.VlcJni` (see `jni/vlc_jni.cpp`). */
internal object VlcJni {

    init {
        NativeLoader.load()
    }

    external fun nativeVersion(): String
    external fun nativeLastError(): String?
    external fun nativeClearError()

    external fun nativeCreateInstance(options: Array<String>): Long
    external fun nativeReleaseInstance(handle: Long)

    external fun nativeCreateMedia(instance: Long, path: String): Long
    external fun nativeCreateMediaLocation(instance: Long, location: String): Long
    external fun nativeRetainMedia(media: Long)
    external fun nativeReleaseMedia(media: Long)
    external fun nativeMediaMrl(media: Long): String?
    external fun nativeMediaState(media: Long): Int
    external fun nativeMediaDuration(media: Long): Long
    external fun nativeMediaParsedStatus(media: Long): Int
    external fun nativeMediaAddOption(media: Long, option: String)
    external fun nativeMediaGetMeta(media: Long, meta: Int): String?
    external fun nativeMediaSetMeta(media: Long, meta: Int, value: String): Boolean
    external fun nativeMediaParse(media: Long, timeoutMs: Long): Boolean
    external fun nativeMediaTracks(media: Long): Array<Any?>

    external fun nativeCreatePlayer(instance: Long, callback: VlcEventCallback): Long
    external fun nativeReleasePlayer(handle: Long)
    external fun nativePlayerSetMedia(handle: Long, media: Long)
    external fun nativePlayerPlay(handle: Long)
    external fun nativePlayerSetPause(handle: Long, paused: Boolean)
    external fun nativePlayerStop(handle: Long)
    external fun nativePlayerIsPlaying(handle: Long): Boolean
    external fun nativePlayerState(handle: Long): Int
    external fun nativePlayerTime(handle: Long): Long
    external fun nativePlayerSetTime(handle: Long, time: Long)
    external fun nativePlayerPosition(handle: Long): Float
    external fun nativePlayerSetPosition(handle: Long, position: Float)
    external fun nativePlayerLength(handle: Long): Long
    external fun nativePlayerRate(handle: Long): Float
    external fun nativePlayerSetRate(handle: Long, rate: Float)
    external fun nativePlayerVolume(handle: Long): Int
    external fun nativePlayerSetVolume(handle: Long, volume: Int)
    external fun nativePlayerMute(handle: Long): Boolean
    external fun nativePlayerSetMute(handle: Long, muted: Boolean)
    external fun nativePlayerAudioTrack(handle: Long): Int
    external fun nativePlayerSetAudioTrack(handle: Long, track: Int)
    external fun nativePlayerVideoTrack(handle: Long): Int
    external fun nativePlayerSetVideoTrack(handle: Long, track: Int)
    external fun nativePlayerSpuTrack(handle: Long): Int
    external fun nativePlayerSetSpuTrack(handle: Long, track: Int)
    external fun nativePlayerTrackDescriptions(handle: Long, type: Int): Array<Any?>
    external fun nativePlayerVideoSize(handle: Long): LongArray?
    external fun nativePlayerHasVout(handle: Long): Boolean
    external fun nativePlayerFps(handle: Long): Float
    external fun nativePlayerTitle(handle: Long): Int
    external fun nativePlayerSetTitle(handle: Long, title: Int)
    external fun nativePlayerTitleCount(handle: Long): Int
    external fun nativePlayerChapter(handle: Long): Int
    external fun nativePlayerSetChapter(handle: Long, chapter: Int)
    external fun nativePlayerChapterCount(handle: Long): Int
    external fun nativePlayerAudioDelay(handle: Long): Long
    external fun nativePlayerSetAudioDelay(handle: Long, delay: Long)
    external fun nativePlayerSpuDelay(handle: Long): Long
    external fun nativePlayerSetSpuDelay(handle: Long, delay: Long)
    external fun nativePlayerSetVideoSinkAuto(handle: Long, callback: JvmVideoCallback)
    external fun nativePlayerSetVideoSink(handle: Long, width: Int, height: Int, callback: JvmVideoCallback)
    external fun nativePlayerClearVideoSink(handle: Long)
    external fun nativePlayerSetWindow(handle: Long, window: Long, kind: Int): Boolean
    external fun nativePlayerSnapshot(handle: Long, path: String, width: Int, height: Int): Boolean
    external fun nativePlayerAddSlave(handle: Long, type: Int, uri: String, select: Boolean): Boolean

    external fun nativeCreateMediaList(instance: Long): Long
    external fun nativeReleaseMediaList(list: Long)
    external fun nativeMediaListCount(list: Long): Int
    external fun nativeMediaListAdd(list: Long, media: Long)
    external fun nativeMediaListInsert(list: Long, index: Int, media: Long)
    external fun nativeMediaListRemove(list: Long, index: Int)
    external fun nativeMediaListItemAt(list: Long, index: Int): Long
    external fun nativeMediaListClear(list: Long)

    external fun nativeCreateMediaListPlayer(instance: Long): Long
    external fun nativeReleaseMediaListPlayer(handle: Long)
    external fun nativeMediaListPlayerSetMediaList(handle: Long, list: Long)
    external fun nativeMediaListPlayerSetMediaPlayer(handle: Long, player: Long)
    external fun nativeMediaListPlayerPlay(handle: Long)
    external fun nativeMediaListPlayerPause(handle: Long)
    external fun nativeMediaListPlayerStop(handle: Long)
    external fun nativeMediaListPlayerNext(handle: Long)
    external fun nativeMediaListPlayerPrevious(handle: Long)
    external fun nativeMediaListPlayerSetMode(handle: Long, mode: Int)
}
