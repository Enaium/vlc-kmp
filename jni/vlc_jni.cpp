/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

// JNI bridge for libVLC 3.x.
//
// The bridge dlopen()s libVLC instead of linking it:
//   * the desktop JNI artifacts stay buildable without a local VLC SDK;
//   * libVLC's plugin/codec tree remains the consumer's package manager's job;
//   * a missing VLC produces one clear loader error instead of a process-level
//     dynamic-linker failure.
//
// libvlc structures never cross the boundary. Kotlin sees scalar handles,
// strings, flat primitive/string arrays and two callback interfaces.

#include <jni.h>
#include <vlc/vlc.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <cstdlib>
#else
#include <dlfcn.h>
#include <unistd.h>
#endif

namespace {

// ---------------------------------------------------------------------------
// Dynamic libVLC loader
// ---------------------------------------------------------------------------

#define VLC_FUNCTIONS(X) \
    X(libvlc_new) \
    X(libvlc_release) \
    X(libvlc_retain) \
    X(libvlc_get_version) \
    X(libvlc_errmsg) \
    X(libvlc_free) \
    X(libvlc_clearerr) \
    X(libvlc_media_new_path) \
    X(libvlc_media_new_location) \
    X(libvlc_media_retain) \
    X(libvlc_media_release) \
    X(libvlc_media_get_mrl) \
    X(libvlc_media_get_state) \
    X(libvlc_media_get_duration) \
    X(libvlc_media_get_parsed_status) \
    X(libvlc_media_add_option) \
    X(libvlc_media_get_meta) \
    X(libvlc_media_set_meta) \
    X(libvlc_media_parse_with_options) \
    X(libvlc_media_tracks_get) \
    X(libvlc_media_tracks_release) \
    X(libvlc_media_player_add_slave) \
    X(libvlc_media_player_new) \
    X(libvlc_media_player_release) \
    X(libvlc_media_player_set_media) \
    X(libvlc_media_player_play) \
    X(libvlc_media_player_set_pause) \
    X(libvlc_media_player_stop) \
    X(libvlc_media_player_is_playing) \
    X(libvlc_media_player_get_state) \
    X(libvlc_media_player_get_time) \
    X(libvlc_media_player_set_time) \
    X(libvlc_media_player_get_position) \
    X(libvlc_media_player_set_position) \
    X(libvlc_media_player_get_length) \
    X(libvlc_media_player_get_rate) \
    X(libvlc_media_player_set_rate) \
    X(libvlc_audio_get_volume) \
    X(libvlc_audio_set_volume) \
    X(libvlc_audio_get_mute) \
    X(libvlc_audio_set_mute) \
    X(libvlc_audio_get_track) \
    X(libvlc_audio_set_track) \
    X(libvlc_video_get_track) \
    X(libvlc_video_set_track) \
    X(libvlc_video_get_spu) \
    X(libvlc_video_set_spu) \
    X(libvlc_audio_get_track_description) \
    X(libvlc_video_get_track_description) \
    X(libvlc_video_get_spu_description) \
    X(libvlc_track_description_list_release) \
    X(libvlc_video_get_size) \
    X(libvlc_media_player_has_vout) \
    X(libvlc_media_player_get_fps) \
    X(libvlc_media_player_get_title) \
    X(libvlc_media_player_set_title) \
    X(libvlc_media_player_get_title_count) \
    X(libvlc_media_player_get_chapter) \
    X(libvlc_media_player_set_chapter) \
    X(libvlc_media_player_get_chapter_count) \
    X(libvlc_audio_get_delay) \
    X(libvlc_audio_set_delay) \
    X(libvlc_video_get_spu_delay) \
    X(libvlc_video_set_spu_delay) \
    X(libvlc_video_set_callbacks) \
    X(libvlc_video_set_format) \
    X(libvlc_video_set_format_callbacks) \
    X(libvlc_video_take_snapshot) \
    X(libvlc_media_player_set_xwindow) \
    X(libvlc_media_player_set_hwnd) \
    X(libvlc_media_player_set_nsobject) \
    X(libvlc_media_player_event_manager) \
    X(libvlc_event_attach) \
    X(libvlc_event_detach) \
    X(libvlc_media_list_new) \
    X(libvlc_media_list_release) \
    X(libvlc_media_list_lock) \
    X(libvlc_media_list_unlock) \
    X(libvlc_media_list_add_media) \
    X(libvlc_media_list_insert_media) \
    X(libvlc_media_list_remove_index) \
    X(libvlc_media_list_count) \
    X(libvlc_media_list_item_at_index) \
    X(libvlc_media_list_player_new) \
    X(libvlc_media_list_player_release) \
    X(libvlc_media_list_player_set_media_list) \
    X(libvlc_media_list_player_set_media_player) \
    X(libvlc_media_list_player_play) \
    X(libvlc_media_list_player_pause) \
    X(libvlc_media_list_player_stop) \
    X(libvlc_media_list_player_next) \
    X(libvlc_media_list_player_previous) \
    X(libvlc_media_list_player_set_playback_mode)

struct VlcApi {
    void *library = nullptr;

#define VLC_DECLARE(name) decltype(&name) p_##name = nullptr;
    VLC_FUNCTIONS(VLC_DECLARE)
#undef VLC_DECLARE
};

VlcApi g_api;
std::mutex g_apiMutex;
std::string g_loadError;
JavaVM *g_vm = nullptr;

void *openLibrary(const char *name) {
#ifdef _WIN32
    return reinterpret_cast<void *>(LoadLibraryA(name));
#else
    return dlopen(name, RTLD_NOW | RTLD_GLOBAL);
#endif
}

std::string loaderError() {
#ifdef _WIN32
    return "LoadLibrary failed (error " + std::to_string(GetLastError()) + ")";
#else
    const char *error = dlerror();
    return error ? error : "dlopen failed";
#endif
}

void *findSymbol(void *library, const char *name) {
#ifdef _WIN32
    return reinterpret_cast<void *>(GetProcAddress(static_cast<HMODULE>(library), name));
#else
    return dlsym(library, name);
#endif
}

std::string envValue(const char *name) {
    const char *value = std::getenv(name);
    return value ? value : "";
}

bool loadFrom(const std::string &path) {
    if (path.empty()) return false;
#ifdef __APPLE__
    // VLC.app's libvlc links libvlccore through @rpath with no LC_RPATH. Load
    // the core from the same directory first so dyld can satisfy that
    // dependency. Homebrew/system installs already have a valid rpath, so a
    // missing sibling is not an error.
    const auto slash = path.find_last_of('/');
    if (slash != std::string::npos) {
        const std::string dir = path.substr(0, slash);
        dlopen((dir + "/libvlccore.dylib").c_str(), RTLD_NOW | RTLD_GLOBAL);
        dlopen((dir + "/libvlccore.9.dylib").c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
#endif
    void *library = openLibrary(path.c_str());
    if (!library) return false;
    if (g_api.library) {
        // Another candidate already won; this process only needs one libVLC.
#ifdef _WIN32
        FreeLibrary(static_cast<HMODULE>(library));
#else
        dlclose(library);
#endif
        return true;
    }
    g_api.library = library;
    return true;
}

void ensureApi() {
    std::lock_guard<std::mutex> lock(g_apiMutex);
    if (g_api.library) return;

    std::vector<std::string> candidates;
    const std::string explicitPath = envValue("VLC_LIBRARY_PATH");
    if (!explicitPath.empty()) candidates.push_back(explicitPath);
    const std::string explicitDir = envValue("VLC_LIBRARY_DIR");

#ifdef _WIN32
    candidates.push_back("libvlc.dll");
    candidates.push_back("vlc\\libvlc.dll");
    candidates.push_back("C:\\Program Files\\VideoLAN\\VLC\\libvlc.dll");
    candidates.push_back("C:\\Program Files (x86)\\VideoLAN\\VLC\\libvlc.dll");
    if (!explicitDir.empty()) candidates.push_back(explicitDir + "\\libvlc.dll");
#elif defined(__APPLE__)
    candidates.push_back("libvlc.dylib");
    candidates.push_back("/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib");
    candidates.push_back("/opt/homebrew/lib/libvlc.dylib");
    candidates.push_back("/usr/local/lib/libvlc.dylib");
    if (!explicitDir.empty()) candidates.push_back(explicitDir + "/libvlc.dylib");
#else
    candidates.push_back("libvlc.so.5");
    candidates.push_back("libvlc.so");
    candidates.push_back("/usr/lib/x86_64-linux-gnu/libvlc.so.5");
    candidates.push_back("/usr/lib/aarch64-linux-gnu/libvlc.so.5");
    candidates.push_back("/usr/lib64/libvlc.so.5");
    if (!explicitDir.empty()) candidates.push_back(explicitDir + "/libvlc.so");
#endif

    for (const std::string &candidate : candidates) {
        if (loadFrom(candidate)) break;
    }
    if (!g_api.library) {
        g_loadError = "Unable to load libVLC. Tried:";
        for (const std::string &candidate : candidates) g_loadError += "\n  " + candidate;
        g_loadError += "\nSet VLC_LIBRARY_PATH to the libvlc shared library.";
        return;
    }

#ifdef __APPLE__
    // A VLC.app build resolves its plugins relative to the bundle. Help it
    // when the library was found by an explicit path instead of the bundle.
    if (envValue("VLC_PLUGIN_PATH").empty()) {
        const char *pluginPath = "/Applications/VLC.app/Contents/MacOS/plugins";
        if (access(pluginPath, R_OK) == 0) {
            setenv("VLC_PLUGIN_PATH", pluginPath, 0);
        }
    }
#endif

#define VLC_LOAD(name) \
    g_api.p_##name = reinterpret_cast<decltype(&name)>(findSymbol(g_api.library, #name)); \
    if (!g_api.p_##name) { \
        g_loadError = std::string("libVLC is missing symbol: ") + #name; \
        return; \
    }
    VLC_FUNCTIONS(VLC_LOAD)
#undef VLC_LOAD
}

void throwByName(JNIEnv *env, const char *className, const std::string &message) {
    jclass cls = env->FindClass(className);
    if (!cls) return;
    env->ThrowNew(cls, message.c_str());
    env->DeleteLocalRef(cls);
}

void requireApi(JNIEnv *env) {
    ensureApi();
    if (!g_api.library) {
        throwByName(env, "java/lang/UnsatisfiedLinkError", g_loadError);
    }
}

#define REQUIRE(condition, message) \
    do { \
        if (!(condition)) { \
            throwByName(env, "cn/enaium/vlc/VlcException", message); \
            return 0; \
        } \
    } while (0)

template <typename T>
T *fromHandle(jlong handle) {
    return reinterpret_cast<T *>(static_cast<intptr_t>(handle));
}

template <typename T>
jlong toHandle(T *pointer) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

// ---------------------------------------------------------------------------
// UTF-8 <-> UTF-16
// ---------------------------------------------------------------------------

std::string utf16ToUtf8(const jchar *chars, jsize length) {
    std::string out;
    out.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

std::u16string utf8ToUtf16(const char *input) {
    std::u16string out;
    if (!input) return out;
    const auto *s = reinterpret_cast<const unsigned char *>(input);
    while (*s) {
        uint32_t c;
        if (s[0] < 0x80) {
            c = s[0];
            s += 1;
        } else if ((s[0] & 0xE0) == 0xC0 && (s[1] & 0xC0) == 0x80) {
            c = ((s[0] & 0x1F) << 6) | (s[1] & 0x3F);
            s += 2;
        } else if ((s[0] & 0xF0) == 0xE0 && (s[1] & 0xC0) == 0x80 && (s[2] & 0xC0) == 0x80) {
            c = ((s[0] & 0x0F) << 12) | ((s[1] & 0x3F) << 6) | (s[2] & 0x3F);
            s += 3;
        } else if ((s[0] & 0xF8) == 0xF0 && (s[1] & 0xC0) == 0x80 &&
                   (s[2] & 0xC0) == 0x80 && (s[3] & 0xC0) == 0x80) {
            c = ((s[0] & 0x07) << 18) | ((s[1] & 0x3F) << 12) |
                ((s[2] & 0x3F) << 6) | (s[3] & 0x3F);
            s += 4;
        } else {
            c = 0xFFFD;
            s += 1;
        }
        if (c > 0xFFFF) {
            c -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (c >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(c));
        }
    }
    return out;
}

jstring newStringUtf8(JNIEnv *env, const char *value) {
    if (!value) return nullptr;
    std::u16string utf16 = utf8ToUtf16(value);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size()));
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    std::string result = utf16ToUtf8(chars, length);
    env->ReleaseStringChars(value, chars);
    return result;
}

// ---------------------------------------------------------------------------
// Event and video callbacks
// ---------------------------------------------------------------------------

struct PlayerContext {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    jmethodID onEvent = nullptr;

    libvlc_media_player_t *player = nullptr;
    libvlc_event_manager_t *manager = nullptr;

    std::recursive_mutex videoMutex;
    jobject videoCallback = nullptr;
    jmethodID videoOnFrame = nullptr;
    jbyteArray videoBytes = nullptr;
    void *videoBuffer = nullptr;
    int videoWidth = 0;
    int videoHeight = 0;
    int videoPitch = 0;
    bool videoActive = false;
    bool videoUsesFormatCallbacks = false;
    std::vector<void *> retiredVideoBuffers;
    std::vector<jbyteArray> retiredVideoBytes;
};

const int kPlayerEventTypes[] = {
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

struct EnvScope {
    JNIEnv *env = nullptr;
    bool attached = false;

    explicit EnvScope(JavaVM *vm) {
        if (!vm) return;
        jint result = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (result != JNI_OK) {
#ifdef __ANDROID__
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
#else
            if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
                attached = true;
            }
#endif
        }
    }

    ~EnvScope() {
        if (attached && vmGlobal()) vmGlobal()->DetachCurrentThread();
    }

    static JavaVM *vmGlobal() { return g_vm; }
};

void mediaPlayerEventCallback(const libvlc_event_t *event, void *opaque) {
    auto *context = static_cast<PlayerContext *>(opaque);
    if (!context || !context->callback) return;
    EnvScope scope(context->vm);
    if (!scope.env) return;

    jlong longValue = 0;
    jdouble doubleValue = 0;
    jint intValue = 0;
    jint trackType = 0;

    switch (event->type) {
        case libvlc_MediaPlayerTimeChanged:
            longValue = static_cast<jlong>(event->u.media_player_time_changed.new_time);
            break;
        case libvlc_MediaPlayerLengthChanged:
            longValue = static_cast<jlong>(event->u.media_player_length_changed.new_length);
            break;
        case libvlc_MediaPlayerPositionChanged:
            doubleValue = event->u.media_player_position_changed.new_position;
            break;
        case libvlc_MediaPlayerBuffering:
            doubleValue = event->u.media_player_buffering.new_cache;
            break;
        case libvlc_MediaPlayerAudioVolume:
            doubleValue = event->u.media_player_audio_volume.volume;
            break;
        case libvlc_MediaPlayerTitleChanged:
            intValue = event->u.media_player_title_changed.new_title;
            break;
        case libvlc_MediaPlayerChapterChanged:
            intValue = event->u.media_player_chapter_changed.new_chapter;
            break;
        case libvlc_MediaPlayerVout:
            intValue = event->u.media_player_vout.new_count;
            break;
        case libvlc_MediaPlayerSeekableChanged:
            intValue = event->u.media_player_seekable_changed.new_seekable;
            break;
        case libvlc_MediaPlayerPausableChanged:
            intValue = event->u.media_player_pausable_changed.new_pausable;
            break;
        case libvlc_MediaPlayerESAdded:
        case libvlc_MediaPlayerESDeleted:
        case libvlc_MediaPlayerESSelected:
            intValue = event->u.media_player_es_changed.i_id;
            trackType = static_cast<jint>(event->u.media_player_es_changed.i_type);
            break;
        default:
            break;
    }

    scope.env->CallVoidMethod(context->callback, context->onEvent,
                              static_cast<jint>(event->type), longValue, doubleValue,
                              intValue, trackType);
    if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
}

void *videoLockCallback(void *opaque, void **planes) {
    auto *context = static_cast<PlayerContext *>(opaque);
    if (!context) return nullptr;
    context->videoMutex.lock();
    if (!context->videoActive || !context->videoBuffer) {
        context->videoMutex.unlock();
        return nullptr;
    }
    if (planes) planes[0] = context->videoBuffer;
    return context->videoBuffer;
}

void videoUnlockCallback(void *opaque, void *, void *const *) {
    auto *context = static_cast<PlayerContext *>(opaque);
    if (context) context->videoMutex.unlock();
}

void videoDisplayCallback(void *opaque, void *) {
    auto *context = static_cast<PlayerContext *>(opaque);
    if (!context) return;
    std::lock_guard<std::recursive_mutex> lock(context->videoMutex);
    if (!context->videoActive || !context->videoBytes) return;
    EnvScope scope(context->vm);
    if (!scope.env) return;
    const jsize size = context->videoPitch * context->videoHeight;
    scope.env->SetByteArrayRegion(context->videoBytes, 0, size,
                                  static_cast<const jbyte *>(context->videoBuffer));
    scope.env->CallVoidMethod(context->videoCallback, context->videoOnFrame,
                              context->videoWidth, context->videoHeight,
                              context->videoPitch, context->videoBytes);
    if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
}

unsigned videoFormatCallback(void **opaque, char *chroma, unsigned *width, unsigned *height,
                             unsigned *pitches, unsigned *lines) {
    auto *context = static_cast<PlayerContext *>(opaque ? *opaque : nullptr);
    if (!context || !width || !height || !pitches || !lines) return 0;
    const jint pixelWidth = static_cast<jint>(*width);
    const jint pixelHeight = static_cast<jint>(*height);
    if (pixelWidth <= 0 || pixelHeight <= 0) return 0;
    const jint pitch = pixelWidth * 4;
    const jsize size = pitch * pixelHeight;

    EnvScope scope(context->vm);
    if (!scope.env) return 0;
    void *buffer = std::malloc(static_cast<size_t>(size));
    if (!buffer) return 0;
    jbyteArray localBytes = scope.env->NewByteArray(size);
    if (!localBytes) {
        std::free(buffer);
        return 0;
    }
    auto *bytes = static_cast<jbyteArray>(scope.env->NewGlobalRef(localBytes));
    scope.env->DeleteLocalRef(localBytes);
    if (!bytes) {
        std::free(buffer);
        return 0;
    }

    {
        std::lock_guard<std::recursive_mutex> lock(context->videoMutex);
        if (!context->videoActive || !context->videoCallback) {
            scope.env->DeleteGlobalRef(bytes);
            std::free(buffer);
            return 0;
        }
        // The format callback may run on the video thread while an in-flight
        // picture still references the previous buffer. Keep it until sink
        // teardown instead of freeing it here.
        if (context->videoBuffer) context->retiredVideoBuffers.push_back(context->videoBuffer);
        if (context->videoBytes) context->retiredVideoBytes.push_back(context->videoBytes);
        context->videoBuffer = buffer;
        context->videoBytes = bytes;
        context->videoWidth = pixelWidth;
        context->videoHeight = pixelHeight;
        context->videoPitch = pitch;
    }

    if (chroma) {
        chroma[0] = 'R';
        chroma[1] = 'V';
        chroma[2] = '3';
        chroma[3] = '2';
    }
    pitches[0] = static_cast<unsigned>(pitch);
    lines[0] = static_cast<unsigned>(pixelHeight);
    return 1;
}

void videoFormatCleanupCallback(void *) {
}

void clearVideoSink(JNIEnv *env, PlayerContext *context) {
    if (!context) return;
    if (context->player) {
        g_api.p_libvlc_video_set_callbacks(context->player, nullptr, nullptr, nullptr, nullptr);
        if (context->videoUsesFormatCallbacks) {
            g_api.p_libvlc_video_set_format_callbacks(context->player, nullptr, nullptr);
        }
    }
    context->videoUsesFormatCallbacks = false;
    {
        std::lock_guard<std::recursive_mutex> lock(context->videoMutex);
        context->videoActive = false;
        if (context->videoCallback) {
            env->DeleteGlobalRef(context->videoCallback);
            context->videoCallback = nullptr;
        }
        if (context->videoBytes) {
            env->DeleteGlobalRef(context->videoBytes);
            context->videoBytes = nullptr;
        }
        std::free(context->videoBuffer);
        context->videoBuffer = nullptr;
        for (void *retired : context->retiredVideoBuffers) std::free(retired);
        context->retiredVideoBuffers.clear();
        for (jbyteArray retired : context->retiredVideoBytes) env->DeleteGlobalRef(retired);
        context->retiredVideoBytes.clear();
        context->videoWidth = 0;
        context->videoHeight = 0;
        context->videoPitch = 0;
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

jobject boxInt(JNIEnv *env, jint value) {
    jclass cls = env->FindClass("java/lang/Integer");
    jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(I)Ljava/lang/Integer;");
    jobject result = env->CallStaticObjectMethod(cls, method, value);
    env->DeleteLocalRef(cls);
    return result;
}

void setObject(JNIEnv *env, jobjectArray array, jsize index, jobject value) {
    env->SetObjectArrayElement(array, index, value);
    if (value) env->DeleteLocalRef(value);
}

jobjectArray trackDescriptions(JNIEnv *env, libvlc_track_description_t *head) {
    if (!head) {
        jclass objectClass = env->FindClass("java/lang/Object");
        jobjectArray empty = env->NewObjectArray(0, objectClass, nullptr);
        env->DeleteLocalRef(objectClass);
        return empty;
    }
    int count = 0;
    for (libvlc_track_description_t *it = head; it; it = it->p_next) ++count;
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(count * 2, objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    int index = 0;
    for (libvlc_track_description_t *it = head; it; it = it->p_next) {
        setObject(env, result, index++, boxInt(env, it->i_id));
        setObject(env, result, index++, newStringUtf8(env, it->psz_name));
    }
    g_api.p_libvlc_track_description_list_release(head);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL Java_cn_enaium_vlc_VlcJni_nativeVersion(JNIEnv *env, jobject) {
    requireApi(env);
    if (env->ExceptionCheck()) return nullptr;
    return newStringUtf8(env, g_api.p_libvlc_get_version());
}

JNIEXPORT jstring JNICALL Java_cn_enaium_vlc_VlcJni_nativeLastError(JNIEnv *env, jobject) {
    if (!g_api.library) return nullptr;
    return newStringUtf8(env, g_api.p_libvlc_errmsg());
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeClearError(JNIEnv *, jobject) {
    if (g_api.library) g_api.p_libvlc_clearerr();
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreateInstance(
        JNIEnv *env, jobject, jobjectArray options) {
    requireApi(env);
    if (env->ExceptionCheck()) return 0;
    const jsize count = options ? env->GetArrayLength(options) : 0;
    std::vector<std::string> storage(static_cast<size_t>(count));
    std::vector<const char *> argv(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(options, i));
        storage[i] = jstringToUtf8(env, value);
        argv[i] = storage[i].c_str();
        env->DeleteLocalRef(value);
    }
    libvlc_instance_t *instance = g_api.p_libvlc_new(
        count, count > 0 ? argv.data() : nullptr);
    return toHandle(instance);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeReleaseInstance(
        JNIEnv *, jobject, jlong handle) {
    if (auto *instance = fromHandle<libvlc_instance_t>(handle)) {
        g_api.p_libvlc_release(instance);
    }
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreateMedia(
        JNIEnv *env, jobject, jlong instanceHandle, jstring path) {
    auto *instance = fromHandle<libvlc_instance_t>(instanceHandle);
    std::string value = jstringToUtf8(env, path);
    return toHandle(g_api.p_libvlc_media_new_path(instance, value.c_str()));
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreateMediaLocation(
        JNIEnv *env, jobject, jlong instanceHandle, jstring location) {
    auto *instance = fromHandle<libvlc_instance_t>(instanceHandle);
    std::string value = jstringToUtf8(env, location);
    return toHandle(g_api.p_libvlc_media_new_location(instance, value.c_str()));
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeRetainMedia(
        JNIEnv *, jobject, jlong handle) {
    if (auto *media = fromHandle<libvlc_media_t>(handle)) g_api.p_libvlc_media_retain(media);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeReleaseMedia(
        JNIEnv *, jobject, jlong handle) {
    if (auto *media = fromHandle<libvlc_media_t>(handle)) g_api.p_libvlc_media_release(media);
}

JNIEXPORT jstring JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaMrl(
        JNIEnv *env, jobject, jlong handle) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    if (!media) return nullptr;
    char *mrl = g_api.p_libvlc_media_get_mrl(media);
    jstring result = newStringUtf8(env, mrl);
    g_api.p_libvlc_free(mrl);
    return result;
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaState(
        JNIEnv *, jobject, jlong handle) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    return media ? static_cast<jint>(g_api.p_libvlc_media_get_state(media)) : 0;
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaDuration(
        JNIEnv *, jobject, jlong handle) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    return media ? static_cast<jlong>(g_api.p_libvlc_media_get_duration(media)) : 0;
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaParsedStatus(
        JNIEnv *, jobject, jlong handle) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    return media ? static_cast<jint>(g_api.p_libvlc_media_get_parsed_status(media)) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaAddOption(
        JNIEnv *env, jobject, jlong handle, jstring option) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    if (!media) return;
    std::string value = jstringToUtf8(env, option);
    g_api.p_libvlc_media_add_option(media, value.c_str());
}

JNIEXPORT jstring JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaGetMeta(
        JNIEnv *env, jobject, jlong handle, jint meta) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    if (!media) return nullptr;
    char *value = g_api.p_libvlc_media_get_meta(media, static_cast<libvlc_meta_t>(meta));
    jstring result = newStringUtf8(env, value);
    g_api.p_libvlc_free(value);
    return result;
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaSetMeta(
        JNIEnv *env, jobject, jlong handle, jint meta, jstring value) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    if (!media) return JNI_FALSE;
    std::string text = jstringToUtf8(env, value);
    g_api.p_libvlc_media_set_meta(media, static_cast<libvlc_meta_t>(meta), text.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaParse(
        JNIEnv *, jobject, jlong handle, jlong timeoutMs) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    if (!media) return JNI_FALSE;
    if (g_api.p_libvlc_media_parse_with_options(
            media, libvlc_media_parse_local, static_cast<int>(timeoutMs)) != 0) {
        return JNI_FALSE;
    }
    const auto deadline = timeoutMs > 0
        ? std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs)
        : std::chrono::steady_clock::time_point::max();
    while (g_api.p_libvlc_media_get_parsed_status(media) == 0) {
        if (std::chrono::steady_clock::now() >= deadline) return JNI_FALSE;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    auto status = g_api.p_libvlc_media_get_parsed_status(media);
    return status != libvlc_media_parsed_status_failed &&
           status != libvlc_media_parsed_status_timeout ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaTracks(
        JNIEnv *env, jobject, jlong handle) {
    auto *media = fromHandle<libvlc_media_t>(handle);
    jclass objectClass = env->FindClass("java/lang/Object");
    if (!media) {
        jobjectArray empty = env->NewObjectArray(0, objectClass, nullptr);
        env->DeleteLocalRef(objectClass);
        return empty;
    }
    libvlc_media_track_t **tracks = nullptr;
    unsigned count = g_api.p_libvlc_media_tracks_get(media, &tracks);
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count * 14), objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    if (!result) {
        g_api.p_libvlc_media_tracks_release(tracks, count);
        return nullptr;
    }
    for (unsigned i = 0; i < count; ++i) {
        const libvlc_media_track_t *track = tracks[i];
        const jsize base = static_cast<jsize>(i * 14);
        setObject(env, result, base + 0, boxInt(env, static_cast<jint>(track->i_type)));
        setObject(env, result, base + 1, boxInt(env, track->i_id));
        setObject(env, result, base + 2, boxInt(env, static_cast<jint>(track->i_codec)));
        setObject(env, result, base + 3, boxInt(env, static_cast<jint>(track->i_bitrate)));
        setObject(env, result, base + 4, newStringUtf8(env, track->psz_language));
        setObject(env, result, base + 5, newStringUtf8(env, track->psz_description));
        jint channels = 0, rate = 0, width = 0, height = 0;
        jint sarNum = 0, sarDen = 0, fpsNum = 0, fpsDen = 0;
        if (track->i_type == libvlc_track_audio && track->audio) {
            channels = static_cast<jint>(track->audio->i_channels);
            rate = static_cast<jint>(track->audio->i_rate);
        } else if (track->i_type == libvlc_track_video && track->video) {
            width = static_cast<jint>(track->video->i_width);
            height = static_cast<jint>(track->video->i_height);
            sarNum = static_cast<jint>(track->video->i_sar_num);
            sarDen = static_cast<jint>(track->video->i_sar_den);
            fpsNum = static_cast<jint>(track->video->i_frame_rate_num);
            fpsDen = static_cast<jint>(track->video->i_frame_rate_den);
        }
        setObject(env, result, base + 6, boxInt(env, channels));
        setObject(env, result, base + 7, boxInt(env, rate));
        setObject(env, result, base + 8, boxInt(env, width));
        setObject(env, result, base + 9, boxInt(env, height));
        setObject(env, result, base + 10, boxInt(env, sarNum));
        setObject(env, result, base + 11, boxInt(env, sarDen));
        setObject(env, result, base + 12, boxInt(env, fpsNum));
        setObject(env, result, base + 13, boxInt(env, fpsDen));
    }
    g_api.p_libvlc_media_tracks_release(tracks, count);
    return result;
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreatePlayer(
        JNIEnv *env, jobject, jlong instanceHandle, jobject callback) {
    auto *instance = fromHandle<libvlc_instance_t>(instanceHandle);
    if (!instance || !callback) return 0;
    auto *context = new PlayerContext();
    context->vm = g_vm;
    context->callback = env->NewGlobalRef(callback);
    if (!context->callback) {
        delete context;
        return 0;
    }
    jclass cls = env->GetObjectClass(callback);
    context->onEvent = env->GetMethodID(cls, "onEvent", "(IJDII)V");
    env->DeleteLocalRef(cls);
    context->player = g_api.p_libvlc_media_player_new(instance);
    if (!context->player) {
        env->DeleteGlobalRef(context->callback);
        delete context;
        return 0;
    }
    context->manager = g_api.p_libvlc_media_player_event_manager(context->player);
    if (context->manager) {
        for (int type : kPlayerEventTypes) {
            g_api.p_libvlc_event_attach(context->manager, type, mediaPlayerEventCallback, context);
        }
    }
    return toHandle(context);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeReleasePlayer(
        JNIEnv *env, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return;
    if (context->manager) {
        for (int type : kPlayerEventTypes) {
            g_api.p_libvlc_event_detach(context->manager, type, mediaPlayerEventCallback, context);
        }
    }
    clearVideoSink(env, context);
    if (context->player) g_api.p_libvlc_media_player_release(context->player);
    if (context->callback) env->DeleteGlobalRef(context->callback);
    delete context;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetMedia(
        JNIEnv *, jobject, jlong handle, jlong mediaHandle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return;
    g_api.p_libvlc_media_player_set_media(
        context->player, fromHandle<libvlc_media_t>(mediaHandle));
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerPlay(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_play(context->player);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetPause(
        JNIEnv *, jobject, jlong handle, jboolean paused) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_pause(context->player, paused ? 1 : 0);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerStop(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_stop(context->player);
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerIsPlaying(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context && g_api.p_libvlc_media_player_is_playing(context->player) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerState(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? static_cast<jint>(g_api.p_libvlc_media_player_get_state(context->player)) : 0;
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerTime(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? static_cast<jlong>(g_api.p_libvlc_media_player_get_time(context->player)) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetTime(
        JNIEnv *, jobject, jlong handle, jlong time) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_time(context->player, time);
}

JNIEXPORT jfloat JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerPosition(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_position(context->player) : 0.0f;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetPosition(
        JNIEnv *, jobject, jlong handle, jfloat position) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_position(context->player, position);
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerLength(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? static_cast<jlong>(g_api.p_libvlc_media_player_get_length(context->player)) : 0;
}

JNIEXPORT jfloat JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerRate(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_rate(context->player) : 1.0f;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetRate(
        JNIEnv *, jobject, jlong handle, jfloat rate) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_rate(context->player, rate);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerVolume(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_audio_get_volume(context->player) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetVolume(
        JNIEnv *, jobject, jlong handle, jint volume) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_audio_set_volume(context->player, volume);
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerMute(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context && g_api.p_libvlc_audio_get_mute(context->player) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetMute(
        JNIEnv *, jobject, jlong handle, jboolean muted) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_audio_set_mute(context->player, muted ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerAudioTrack(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_audio_get_track(context->player) : -1;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetAudioTrack(
        JNIEnv *, jobject, jlong handle, jint track) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_audio_set_track(context->player, track);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerVideoTrack(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_video_get_track(context->player) : -1;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetVideoTrack(
        JNIEnv *, jobject, jlong handle, jint track) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_video_set_track(context->player, track);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSpuTrack(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_video_get_spu(context->player) : -1;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetSpuTrack(
        JNIEnv *, jobject, jlong handle, jint track) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_video_set_spu(context->player, track);
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerTrackDescriptions(
        JNIEnv *env, jobject, jlong handle, jint type) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) {
        return trackDescriptions(env, nullptr);
    }
    libvlc_track_description_t *head = nullptr;
    switch (type) {
        case 0: head = g_api.p_libvlc_audio_get_track_description(context->player); break;
        case 1: head = g_api.p_libvlc_video_get_track_description(context->player); break;
        case 2: head = g_api.p_libvlc_video_get_spu_description(context->player); break;
        default: break;
    }
    return trackDescriptions(env, head);
}

JNIEXPORT jlongArray JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerVideoSize(
        JNIEnv *env, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return nullptr;
    unsigned width = 0, height = 0;
    if (g_api.p_libvlc_video_get_size(context->player, 0, &width, &height) != 0) return nullptr;
    jlongArray result = env->NewLongArray(2);
    jlong values[2] = {static_cast<jlong>(width), static_cast<jlong>(height)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerHasVout(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context && g_api.p_libvlc_media_player_has_vout(context->player) > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerFps(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_fps(context->player) : 0.0f;
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerTitle(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_title(context->player) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetTitle(
        JNIEnv *, jobject, jlong handle, jint title) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_title(context->player, title);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerTitleCount(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_title_count(context->player) : 0;
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerChapter(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_chapter(context->player) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetChapter(
        JNIEnv *, jobject, jlong handle, jint chapter) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_media_player_set_chapter(context->player, chapter);
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerChapterCount(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_media_player_get_chapter_count(context->player) : 0;
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerAudioDelay(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_audio_get_delay(context->player) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetAudioDelay(
        JNIEnv *, jobject, jlong handle, jlong delay) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_audio_set_delay(context->player, delay);
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSpuDelay(
        JNIEnv *, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    return context ? g_api.p_libvlc_video_get_spu_delay(context->player) : 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetSpuDelay(
        JNIEnv *, jobject, jlong handle, jlong delay) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) g_api.p_libvlc_video_set_spu_delay(context->player, delay);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetVideoSinkAuto(
        JNIEnv *env, jobject, jlong handle, jobject callback) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context || !callback) return;
    clearVideoSink(env, context);

    jobject callbackRef = env->NewGlobalRef(callback);
    if (!callbackRef) return;
    jclass cls = env->GetObjectClass(callback);
    jmethodID onFrame = env->GetMethodID(cls, "onFrame", "(III[B)V");
    env->DeleteLocalRef(cls);
    if (!onFrame) {
        env->DeleteGlobalRef(callbackRef);
        return;
    }

    {
        std::lock_guard<std::recursive_mutex> lock(context->videoMutex);
        context->videoCallback = callbackRef;
        context->videoOnFrame = onFrame;
        context->videoActive = true;
    }
    context->videoUsesFormatCallbacks = true;
    g_api.p_libvlc_video_set_callbacks(
        context->player, videoLockCallback, videoUnlockCallback, videoDisplayCallback, context);
    g_api.p_libvlc_video_set_format_callbacks(
        context->player, videoFormatCallback, videoFormatCleanupCallback);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetVideoSink(
        JNIEnv *env, jobject, jlong handle, jint width, jint height, jobject callback) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context || !callback || width <= 0 || height <= 0) return;
    clearVideoSink(env, context);

    const int pitch = width * 4;
    void *buffer = std::malloc(static_cast<size_t>(pitch) * height);
    if (!buffer) {
        throwByName(env, "java/lang/OutOfMemoryError", "Unable to allocate video frame buffer");
        return;
    }
    jbyteArray localBytes = env->NewByteArray(pitch * height);
    if (!localBytes) {
        std::free(buffer);
        return;
    }
    auto *bytes = static_cast<jbyteArray>(env->NewGlobalRef(localBytes));
    env->DeleteLocalRef(localBytes);
    if (!bytes) {
        std::free(buffer);
        return;
    }
    jobject callbackRef = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jmethodID onFrame = env->GetMethodID(cls, "onFrame", "(III[B)V");
    env->DeleteLocalRef(cls);

    {
        std::lock_guard<std::recursive_mutex> lock(context->videoMutex);
        context->videoCallback = callbackRef;
        context->videoOnFrame = onFrame;
        context->videoBytes = bytes;
        context->videoBuffer = buffer;
        context->videoWidth = width;
        context->videoHeight = height;
        context->videoPitch = pitch;
        context->videoActive = true;
    }

    g_api.p_libvlc_video_set_callbacks(
        context->player, videoLockCallback, videoUnlockCallback, videoDisplayCallback, context);
    g_api.p_libvlc_video_set_format(
        context->player, "RV32", static_cast<unsigned>(width),
        static_cast<unsigned>(height), static_cast<unsigned>(pitch));
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerClearVideoSink(
        JNIEnv *env, jobject, jlong handle) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (context) clearVideoSink(env, context);
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSetWindow(
        JNIEnv *, jobject, jlong handle, jlong window, jint kind) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return JNI_FALSE;
    switch (kind) {
        case 0:
#if defined(__linux__) && !defined(__ANDROID__)
            g_api.p_libvlc_media_player_set_xwindow(context->player, static_cast<uint32_t>(window));
            return JNI_TRUE;
#else
            return JNI_FALSE;
#endif
        case 1:
#ifdef _WIN32
            g_api.p_libvlc_media_player_set_hwnd(
                context->player, reinterpret_cast<void *>(static_cast<intptr_t>(window)));
            return JNI_TRUE;
#else
            return JNI_FALSE;
#endif
        case 2:
#ifdef __APPLE__
            g_api.p_libvlc_media_player_set_nsobject(
                context->player, reinterpret_cast<void *>(static_cast<intptr_t>(window)));
            return JNI_TRUE;
#else
            return JNI_FALSE;
#endif
        default:
            return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerSnapshot(
        JNIEnv *env, jobject, jlong handle, jstring path, jint width, jint height) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return JNI_FALSE;
    std::string value = jstringToUtf8(env, path);
    return g_api.p_libvlc_video_take_snapshot(
        context->player, 0, value.c_str(), static_cast<unsigned>(width),
        static_cast<unsigned>(height)) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_cn_enaium_vlc_VlcJni_nativePlayerAddSlave(
        JNIEnv *env, jobject, jlong handle, jint type, jstring uri, jboolean select) {
    auto *context = fromHandle<PlayerContext>(handle);
    if (!context) return JNI_FALSE;
    libvlc_media_slave_type_t slaveType;
    if (type == 3) {
        slaveType = libvlc_media_slave_type_subtitle;
    } else if (type == 1) {
        slaveType = libvlc_media_slave_type_audio;
    } else {
        return JNI_FALSE;
    }
    std::string value = jstringToUtf8(env, uri);
    return g_api.p_libvlc_media_player_add_slave(
        context->player, slaveType, value.c_str(), select == JNI_TRUE ? 1 : 0) == 0
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreateMediaList(
        JNIEnv *, jobject, jlong instanceHandle) {
    auto *instance = fromHandle<libvlc_instance_t>(instanceHandle);
    return toHandle(instance ? g_api.p_libvlc_media_list_new(instance) : nullptr);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeReleaseMediaList(
        JNIEnv *, jobject, jlong handle) {
    if (auto *list = fromHandle<libvlc_media_list_t>(handle)) {
        g_api.p_libvlc_media_list_release(list);
    }
}

JNIEXPORT jint JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListCount(
        JNIEnv *, jobject, jlong handle) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return 0;
    g_api.p_libvlc_media_list_lock(list);
    int count = g_api.p_libvlc_media_list_count(list);
    g_api.p_libvlc_media_list_unlock(list);
    return count;
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListAdd(
        JNIEnv *, jobject, jlong handle, jlong mediaHandle) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return;
    g_api.p_libvlc_media_list_lock(list);
    g_api.p_libvlc_media_list_add_media(list, fromHandle<libvlc_media_t>(mediaHandle));
    g_api.p_libvlc_media_list_unlock(list);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListInsert(
        JNIEnv *, jobject, jlong handle, jint index, jlong mediaHandle) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return;
    g_api.p_libvlc_media_list_lock(list);
    g_api.p_libvlc_media_list_insert_media(
        list, fromHandle<libvlc_media_t>(mediaHandle), index);
    g_api.p_libvlc_media_list_unlock(list);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListRemove(
        JNIEnv *, jobject, jlong handle, jint index) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return;
    g_api.p_libvlc_media_list_lock(list);
    g_api.p_libvlc_media_list_remove_index(list, index);
    g_api.p_libvlc_media_list_unlock(list);
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListItemAt(
        JNIEnv *, jobject, jlong handle, jint index) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return 0;
    g_api.p_libvlc_media_list_lock(list);
    libvlc_media_t *media = g_api.p_libvlc_media_list_item_at_index(list, index);
    g_api.p_libvlc_media_list_unlock(list);
    return toHandle(media);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListClear(
        JNIEnv *, jobject, jlong handle) {
    auto *list = fromHandle<libvlc_media_list_t>(handle);
    if (!list) return;
    g_api.p_libvlc_media_list_lock(list);
    for (int index = g_api.p_libvlc_media_list_count(list) - 1; index >= 0; --index) {
        g_api.p_libvlc_media_list_remove_index(list, index);
    }
    g_api.p_libvlc_media_list_unlock(list);
}

JNIEXPORT jlong JNICALL Java_cn_enaium_vlc_VlcJni_nativeCreateMediaListPlayer(
        JNIEnv *, jobject, jlong instanceHandle) {
    auto *instance = fromHandle<libvlc_instance_t>(instanceHandle);
    return toHandle(instance ? g_api.p_libvlc_media_list_player_new(instance) : nullptr);
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeReleaseMediaListPlayer(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_release(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerSetMediaList(
        JNIEnv *, jobject, jlong handle, jlong listHandle) {
    auto *player = fromHandle<libvlc_media_list_player_t>(handle);
    if (player) {
        g_api.p_libvlc_media_list_player_set_media_list(
            player, fromHandle<libvlc_media_list_t>(listHandle));
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerSetMediaPlayer(
        JNIEnv *, jobject, jlong handle, jlong playerHandle) {
    auto *listPlayer = fromHandle<libvlc_media_list_player_t>(handle);
    auto *player = fromHandle<PlayerContext>(playerHandle);
    if (listPlayer) {
        g_api.p_libvlc_media_list_player_set_media_player(
            listPlayer, player ? player->player : nullptr);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerPlay(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_play(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerPause(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_pause(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerStop(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_stop(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerNext(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_next(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerPrevious(
        JNIEnv *, jobject, jlong handle) {
    if (auto *player = fromHandle<libvlc_media_list_player_t>(handle)) {
        g_api.p_libvlc_media_list_player_previous(player);
    }
}

JNIEXPORT void JNICALL Java_cn_enaium_vlc_VlcJni_nativeMediaListPlayerSetMode(
        JNIEnv *, jobject, jlong handle, jint mode) {
    auto *player = fromHandle<libvlc_media_list_player_t>(handle);
    if (player) {
        g_api.p_libvlc_media_list_player_set_playback_mode(
            player, static_cast<libvlc_playback_mode_t>(mode));
    }
}

} // extern "C"
