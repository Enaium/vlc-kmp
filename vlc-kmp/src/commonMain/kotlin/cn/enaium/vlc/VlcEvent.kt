/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

/**
 * Events emitted by [VlcMediaPlayer] and [VlcMedia]. Values are sampled from
 * the event payload, so no additional libVLC call is made from the callback
 * thread.
 */
sealed class VlcEvent {
    data class MediaMetaChanged(val meta: VlcMeta) : VlcEvent()
    data class MediaDurationChanged(val duration: Long) : VlcEvent()
    data class MediaParsedChanged(val status: VlcParsedStatus) : VlcEvent()
    data class MediaStateChanged(val state: VlcState) : VlcEvent()
    data class MediaSubItemAdded(val mrl: String?) : VlcEvent()

    data class PlayerMediaChanged(val mrl: String?) : VlcEvent()
    data object PlayerNothingSpecial : VlcEvent()
    data object PlayerOpening : VlcEvent()
    data class PlayerBuffering(val cache: Float) : VlcEvent()
    data object PlayerPlaying : VlcEvent()
    data object PlayerPaused : VlcEvent()
    data object PlayerStopped : VlcEvent()
    data object PlayerEndReached : VlcEvent()
    data object PlayerEncounteredError : VlcEvent()
    data class PlayerTimeChanged(val time: Long) : VlcEvent()
    data class PlayerPositionChanged(val position: Float) : VlcEvent()
    data class PlayerSeekableChanged(val seekable: Boolean) : VlcEvent()
    data class PlayerPausableChanged(val pausable: Boolean) : VlcEvent()
    data class PlayerTitleChanged(val title: Int) : VlcEvent()
    data class PlayerChapterChanged(val chapter: Int) : VlcEvent()
    data class PlayerLengthChanged(val length: Long) : VlcEvent()
    data class PlayerVout(val count: Int) : VlcEvent()
    data class PlayerAudioVolume(val volume: Int) : VlcEvent()
    data class PlayerMuted(val muted: Boolean) : VlcEvent()
    data class PlayerEsAdded(val type: VlcTrackType, val id: Int) : VlcEvent()
    data class PlayerEsDeleted(val type: VlcTrackType, val id: Int) : VlcEvent()
    data class PlayerEsSelected(val type: VlcTrackType, val id: Int) : VlcEvent()
    data object PlayerSnapshotTaken : VlcEvent()
    data object MediaListEndReached : VlcEvent()
}
