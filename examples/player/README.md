# vlc-kmp player example

A compact libVLC player built with `vlc-kmp`, `sdl-kmp` and `imgui-kmp`.
The same `PlayerApp` runs on JVM, Kotlin/Native and Android; the browser target
uses the wasm `HTMLMediaElement` backend and positions the video element under
the ImGui canvas.

The video is letterboxed at its intrinsic aspect ratio and never stretched to
the window. The libVLC sink is configured from the decoded format, so resizing
the window only changes the destination rectangle, not the frame stride.

## Desktop JVM

```bash
./gradlew :examples:player:jvmRun
./gradlew :examples:player:jvmRun --args="--file /path/to/movie.mp4"
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:player:jvmRun --args="--no-audio --frames 120"
```

libVLC is located at runtime by the JNI bridge (`/Applications/VLC.app` on
macOS, `libvlc.so` on Linux, the VLC install on Windows). Override with
`VLC_LIBRARY_PATH`.

## Kotlin/Native

```bash
./gradlew :examples:player:linkDebugExecutableMacosArm64
SDL_VIDEO_DRIVER=dummy VLC_KMP_FRAMES=120 VLC_KMP_NO_AUDIO=1 \
  VLC_KMP_FILE="$PWD/examples/player/src/commonMain/resources/sample.mp4" \
  ./examples/player/build/bin/macosArm64/debugExecutable/player.kexe
```

Supported targets: `macosArm64`, `macosX64`, `linuxX64`, `linuxArm64`,
`mingwX64`. libVLC is linked from the host prefix detected in
`build.gradle.kts`; set `-Pvlc.library.dir=...` or `VLC_LIBRARY_DIR` if needed.

## Android

```bash
./gradlew :examples:player:android:assembleDebug
adb install examples/player/android/build/outputs/apk/debug/android-debug.apk
```

The APK contains SDL, ImGui, the `vlc_jni` bridge, libVLC and its plugins for
`arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`.

## Browser (wasmJs)

```bash
./gradlew :examples:player:copyWebAssets
python3 -m http.server -d examples/player/build/browserApp 8080
```

Open `http://localhost:8080`. Chrome/Edge/Firefox play the bundled
`sample.mp4` through `HTMLMediaElement`; the ImGui controls are drawn on an
SDL canvas above it.

## Controls

- path/URL field + **Load**
- **Play/Pause**, **Stop**
- **Mute**, volume slider
- seek bar and time display
- **Fullscreen**
- Space toggles playback, arrows seek ±5 s, up/down change volume

`--no-audio` / `VLC_KMP_NO_AUDIO=1` selects libVLC's dummy audio output for
headless CI. `--frames` / `VLC_KMP_FRAMES` limits the render loop.
