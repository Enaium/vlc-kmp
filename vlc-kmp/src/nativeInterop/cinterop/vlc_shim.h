/*
 * C shim for Kotlin/Native cinterop.
 *
 * libVLC exposes event payloads and media-track details through anonymous
 * unions, which cinterop does not map ergonomically. These static inline
 * helpers flatten them into named fields.
 */

#ifndef VLC_KMP_SHIM_H
#define VLC_KMP_SHIM_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vlc/vlc.h>

#ifndef _WIN32
#include <unistd.h>
#endif

static inline void vlc_kmp_copy_pixels(void *destination, const void *source, int size)
{
    if (destination != NULL && source != NULL && size > 0)
        memcpy(destination, source, (size_t) size);
}

static inline void vlc_kmp_ensure_plugin_path(void)
{
#ifdef __APPLE__
    if (getenv("VLC_PLUGIN_PATH") == NULL) {
        const char *path = "/Applications/VLC.app/Contents/MacOS/plugins";
        if (access(path, R_OK) == 0)
            setenv("VLC_PLUGIN_PATH", path, 0);
    }
#endif
}

typedef struct vlc_kmp_track {
    int type;
    int id;
    uint32_t codec;
    int bitrate;
    const char *language;
    const char *description;
    unsigned channels;
    unsigned sample_rate;
    unsigned width;
    unsigned height;
    unsigned sar_num;
    unsigned sar_den;
    unsigned frame_rate_num;
    unsigned frame_rate_den;
} vlc_kmp_track;

static inline void vlc_kmp_read_track(const libvlc_media_track_t *track,
                                      vlc_kmp_track *out)
{
    if (track == NULL || out == NULL)
        return;
    out->type = (int) track->i_type;
    out->id = track->i_id;
    out->codec = track->i_codec;
    out->bitrate = (int) track->i_bitrate;
    out->language = track->psz_language;
    out->description = track->psz_description;
    out->channels = 0;
    out->sample_rate = 0;
    out->width = 0;
    out->height = 0;
    out->sar_num = 0;
    out->sar_den = 0;
    out->frame_rate_num = 0;
    out->frame_rate_den = 0;
    switch (track->i_type) {
        case libvlc_track_audio:
            if (track->audio != NULL) {
                out->channels = track->audio->i_channels;
                out->sample_rate = track->audio->i_rate;
            }
            break;
        case libvlc_track_video:
            if (track->video != NULL) {
                out->width = track->video->i_width;
                out->height = track->video->i_height;
                out->sar_num = track->video->i_sar_num;
                out->sar_den = track->video->i_sar_den;
                out->frame_rate_num = track->video->i_frame_rate_num;
                out->frame_rate_den = track->video->i_frame_rate_den;
            }
            break;
        default:
            break;
    }
}

static inline int vlc_kmp_event_type(const libvlc_event_t *event)
{
    return event != NULL ? event->type : -1;
}

static inline int64_t vlc_kmp_event_time(const libvlc_event_t *event)
{
    return event != NULL ? (int64_t) event->u.media_player_time_changed.new_time : 0;
}

static inline int64_t vlc_kmp_event_length(const libvlc_event_t *event)
{
    return event != NULL ? (int64_t) event->u.media_player_length_changed.new_length : 0;
}

static inline int64_t vlc_kmp_event_duration(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_duration_changed.new_duration : 0;
}

static inline float vlc_kmp_event_position(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_player_position_changed.new_position : 0.0f;
}

static inline float vlc_kmp_event_buffering(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_player_buffering.new_cache : 0.0f;
}

static inline float vlc_kmp_event_volume(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_player_audio_volume.volume : 0.0f;
}

static inline int vlc_kmp_event_meta(const libvlc_event_t *event)
{
    return event != NULL ? (int) event->u.media_meta_changed.meta_type : 0;
}

static inline int vlc_kmp_event_state(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_state_changed.new_state : 0;
}

static inline int vlc_kmp_event_parsed_status(const libvlc_event_t *event)
{
    return event != NULL ? event->u.media_parsed_changed.new_status : 0;
}

static inline int vlc_kmp_event_int(const libvlc_event_t *event)
{
    if (event == NULL)
        return 0;
    switch (event->type) {
        case libvlc_MediaPlayerTitleChanged:
            return event->u.media_player_title_changed.new_title;
        case libvlc_MediaPlayerChapterChanged:
            return event->u.media_player_chapter_changed.new_chapter;
        case libvlc_MediaPlayerVout:
            return event->u.media_player_vout.new_count;
        case libvlc_MediaPlayerSeekableChanged:
            return event->u.media_player_seekable_changed.new_seekable;
        case libvlc_MediaPlayerPausableChanged:
            return event->u.media_player_pausable_changed.new_pausable;
        case libvlc_MediaPlayerESAdded:
        case libvlc_MediaPlayerESDeleted:
        case libvlc_MediaPlayerESSelected:
            return event->u.media_player_es_changed.i_id;
        default:
            return 0;
    }
}

static inline int vlc_kmp_event_track_type(const libvlc_event_t *event)
{
    if (event == NULL)
        return 0;
    switch (event->type) {
        case libvlc_MediaPlayerESAdded:
        case libvlc_MediaPlayerESDeleted:
        case libvlc_MediaPlayerESSelected:
            return (int) event->u.media_player_es_changed.i_type;
        default:
            return 0;
    }
}

static const int vlc_kmp_player_events[] = {
    libvlc_MediaPlayerMediaChanged,
    libvlc_MediaPlayerNothingSpecial,
    libvlc_MediaPlayerOpening,
    libvlc_MediaPlayerBuffering,
    libvlc_MediaPlayerPlaying,
    libvlc_MediaPlayerPaused,
    libvlc_MediaPlayerStopped,
    libvlc_MediaPlayerEndReached,
    libvlc_MediaPlayerEncounteredError,
    libvlc_MediaPlayerTimeChanged,
    libvlc_MediaPlayerPositionChanged,
    libvlc_MediaPlayerSeekableChanged,
    libvlc_MediaPlayerPausableChanged,
    libvlc_MediaPlayerTitleChanged,
    libvlc_MediaPlayerSnapshotTaken,
    libvlc_MediaPlayerLengthChanged,
    libvlc_MediaPlayerVout,
    libvlc_MediaPlayerESAdded,
    libvlc_MediaPlayerESDeleted,
    libvlc_MediaPlayerESSelected,
    libvlc_MediaPlayerMuted,
    libvlc_MediaPlayerUnmuted,
    libvlc_MediaPlayerAudioVolume,
    libvlc_MediaPlayerChapterChanged,
};

static const int vlc_kmp_media_events[] = {
    libvlc_MediaMetaChanged,
    libvlc_MediaSubItemAdded,
    libvlc_MediaDurationChanged,
    libvlc_MediaParsedChanged,
    libvlc_MediaStateChanged,
};

static inline void vlc_kmp_attach_player_events(libvlc_event_manager_t *manager,
                                                libvlc_callback_t callback,
                                                void *opaque)
{
    if (manager == NULL)
        return;
    for (unsigned i = 0; i < sizeof(vlc_kmp_player_events) / sizeof(vlc_kmp_player_events[0]); ++i)
        libvlc_event_attach(manager, vlc_kmp_player_events[i], callback, opaque);
}

static inline void vlc_kmp_detach_player_events(libvlc_event_manager_t *manager,
                                                libvlc_callback_t callback,
                                                void *opaque)
{
    if (manager == NULL)
        return;
    for (unsigned i = 0; i < sizeof(vlc_kmp_player_events) / sizeof(vlc_kmp_player_events[0]); ++i)
        libvlc_event_detach(manager, vlc_kmp_player_events[i], callback, opaque);
}

static inline void vlc_kmp_attach_media_events(libvlc_event_manager_t *manager,
                                               libvlc_callback_t callback,
                                               void *opaque)
{
    if (manager == NULL)
        return;
    for (unsigned i = 0; i < sizeof(vlc_kmp_media_events) / sizeof(vlc_kmp_media_events[0]); ++i)
        libvlc_event_attach(manager, vlc_kmp_media_events[i], callback, opaque);
}

static inline void vlc_kmp_detach_media_events(libvlc_event_manager_t *manager,
                                               libvlc_callback_t callback,
                                               void *opaque)
{
    if (manager == NULL)
        return;
    for (unsigned i = 0; i < sizeof(vlc_kmp_media_events) / sizeof(vlc_kmp_media_events[0]); ++i)
        libvlc_event_detach(manager, vlc_kmp_media_events[i], callback, opaque);
}

#endif /* VLC_KMP_SHIM_H */

