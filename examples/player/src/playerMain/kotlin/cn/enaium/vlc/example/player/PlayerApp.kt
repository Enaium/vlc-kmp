/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc.example.player

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.vlc.Vlc
import cn.enaium.vlc.VlcEngine
import cn.enaium.vlc.VlcMedia
import cn.enaium.vlc.VlcMediaPlayer
import cn.enaium.vlc.VlcSize
import cn.enaium.vlc.VlcState
import cn.enaium.vlc.VlcVideoFrame
import kotlinx.atomicfu.atomic
import kotlin.math.max
import kotlin.math.roundToInt

/** Host-owned frame loop; the browser drives it from a timer. */
typealias PlayerFrameDriver = (step: () -> Boolean, done: () -> Unit) -> Unit

/** Positions the HTML video under the shared ImGui canvas (web only). */
typealias WebVideoSurface = (
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    displayWidth: Float,
    displayHeight: Float,
    visible: Boolean,
) -> Unit

private class PendingFrame(
    val width: Int,
    val height: Int,
    val pitch: Int,
    val pixels: ByteArray,
)

private class FitRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * A complete player: SDL3 window + SDL 2D renderer + Dear ImGui controls.
 * Desktop/native/Android receive libVLC's RV32 software frames and upload them
 * to an SDL streaming texture; the browser positions an HTML video element
 * under the same ImGui UI.
 */
class PlayerApp(
    private val initialPath: String?,
    private val maxFrames: Int = Int.MAX_VALUE,
    private val noAudio: Boolean = false,
    private val providedEngine: VlcEngine? = null,
    private val webVideoSurface: WebVideoSurface? = null,
    private val uiScaleProvider: ((SDLWindow) -> Float)? = null,
) {

    private lateinit var window: SDLWindow
    private lateinit var renderer: SDLRenderer
    private lateinit var imgui: ImGuiSdlBackend
    private lateinit var backend: ImGuiSdlRendererBackend
    private lateinit var engine: VlcEngine
    private lateinit var player: VlcMediaPlayer

    private var media: VlcMedia? = null
    private var texture: SDLTexture? = null
    private var textureId: ImTextureID? = null

    private val pendingFrame = atomic<PendingFrame?>(null)
    private val frameWidth = atomic(0)
    private val frameHeight = atomic(0)
    private var mediaPath = initialPath.orEmpty()
    private var pendingLoad: String? = initialPath?.takeIf { it.isNotBlank() }
    private var lastError: String? = null
    private var seekDragging = false
    private var seekTarget = 0f
    private var finished = false
    private var firstFrameLogged = false
    private var androidUi = false
    private var uiScale = 1f

    private val isWeb: Boolean
        get() = Vlc.platform == "js" || Vlc.platform == "wasmJs"

    fun run(driver: PlayerFrameDriver? = null) {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            check(SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                "SDL_Init failed: ${SDL.error()}"
            }
            println("video init fell back to the dummy driver — running headless")
        }
        androidUi = !isWeb && SDL.getCurrentVideoDriver() == "android"
        if (androidUi) {
            SDL.setHint("SDL_ORIENTATIONS", "LandscapeLeft LandscapeRight")
            SDL.setHint("SDL_TOUCH_MOUSE_EVENTS", "1")
        }

        window = SDL.createWindow(
            title = "vlc-kmp player",
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.HIGH_PIXEL_DENSITY or
                (if (androidUi) 0u else SDLWindowFlags.RESIZABLE),
        )
        if (androidUi) {
            uiScale = runCatching { uiScaleProvider?.invoke(window) }.getOrNull()
                ?.takeIf { it.isFinite() && it > 1f } ?: 1f
        }
        renderer = SDL.createRenderer(window)
        val context = ImGui.createContext()
        imgui = ImGuiSdlBackend(window)
        backend = ImGuiSdlRendererBackend(renderer)

        engine = providedEngine ?: Vlc.create(
            buildList {
                add("--no-video-title-show")
                // The player consumes CPU frames through libVLC's vmem output;
                // hardware decode would only add a GPU->CPU copy.
                add("--avcodec-hw=none")
                if (noAudio) add("--aout=dummy")
            },
        )
        player = engine.createMediaPlayer()

        try {
            imgui.init()
            val fonts = ImGui.getIO().fonts
            val density = if (androidUi) {
                uiScale
            } else {
                maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
            }
            fonts.addFontDefault(ImFontConfig(sizePixels = 15f * density, rasterizerDensity = density))
            check(fonts.build()) { "font atlas build failed" }
            val texData = fonts.getTexDataAsRGBA32()
            val fontTexture = backend.uploadFontTexture(texData.pixels, texData.width, texData.height)
            fonts.setTexID(fontTexture)

            println("vlc-kmp UI ${Vlc.version} via ${Vlc.platform}, engine ${engine.version}")
            if (!isWeb) {
                // Let libVLC report the decoded size. The sink never follows
                // the window size, so resizing only changes the destination
                // rectangle and cannot tear the frame stride.
                player.setVideoSink { frame -> onVideoFrame(frame) }
            }
            var frame = 0
            fun step(): Boolean {
                while (true) {
                    val event = SDL.pollEvent() ?: break
                    when (event) {
                        is SDLEvent.Quit -> return false
                        is SDLEvent.Window -> if (event.type == SDLWindowEventType.CLOSE_REQUESTED) return false
                        is SDLEvent.MouseButton -> {
                            ImGui.getIO().addMousePosEvent(event.x, event.y)
                            imgui.processEvent(event)
                        }
                        else -> imgui.processEvent(event)
                    }
                }

                imgui.newFrame()
                drawUi()
                ImGui.render()
                renderer.drawColor = cn.enaium.sdl.SDLColor(12, 13, 18, 255)
                renderer.clear()
                backend.renderDrawData(ImGui.getDrawData())
                renderer.present()
                if (!isWeb) SDL.delay(1)
                frame++
                return frame < maxFrames
            }

            if (driver != null) {
                driver(::step) { finish(context) }
            } else {
                while (step()) {
                }
                finish(context)
            }
        } catch (t: Throwable) {
            finish(context)
            throw t
        }
    }

    private fun onVideoFrame(frame: VlcVideoFrame) {
        frameWidth.value = frame.width
        frameHeight.value = frame.height
        pendingFrame.value = PendingFrame(
            width = frame.width,
            height = frame.height,
            pitch = frame.pitch,
            pixels = frame.pixels.copyOf(),
        )
        if (!firstFrameLogged) {
            firstFrameLogged = true
            println("video frame: ${frame.width}x${frame.height} pitch=${frame.pitch}")
        }
    }

    private fun drawUi() {
        val io = ImGui.getIO()
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(io.displaySize, ImGuiCond.ALWAYS)
        val flags = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS
        ImGui.begin("vlc-kmp player", flags = flags)

        val duration = player.duration / 1000.0
        val position = player.time / 1000.0
        val paused = player.state != VlcState.Playing
        val volume = player.volume.coerceIn(0, 100)
        val muted = player.muted

        ImGui.text("vlc-kmp")
        ImGui.sameLine()
        if (ImGui.button(if (window.fullscreen) "Windowed" else "Fullscreen", ImVec2(110f * uiScale, 0f))) {
            window.fullscreen = !window.fullscreen
        }
        ImGui.setNextItemWidth((ImGui.getContentRegionAvail().x - 76f * uiScale).coerceAtLeast(120f * uiScale))
        ImGui.inputText("##path", mediaPath)?.let { mediaPath = it }
        ImGui.sameLine()
        if (ImGui.button("Load", ImVec2(64f * uiScale, 0f))) load(mediaPath)

        ImGui.spacing()
        val available = ImGui.getContentRegionAvail()
        val controlHeight = ImGui.getFrameHeightWithSpacing() * 2 + 28f * uiScale
        val videoWidth = available.x.coerceAtLeast(160f)
        val videoHeight = (available.y - controlHeight).coerceAtLeast(120f)
        pendingLoad?.let { path ->
            pendingLoad = null
            load(path)
        }

        val start = ImGui.getCursorScreenPos()
        val end = ImVec2(start.x + videoWidth, start.y + videoHeight)
        val drawList = ImGui.getWindowDrawList()
        drawList.DrawRectFilled(start, end, 0xFF101218.toInt())
        if (!isWeb) updateTexture()

        val fit = fitRect(start, videoWidth, videoHeight, sourceSize())
        if (isWeb) {
            webVideoSurface?.invoke(
                fit.x,
                fit.y,
                fit.width,
                fit.height,
                io.displaySize.x,
                io.displaySize.y,
                true,
            )
        } else {
            val currentTexture = textureId
            if (currentTexture != null) {
                val allocatedWidth = frameWidth.value
                val allocatedHeight = frameHeight.value
                val source = sourceSize()
                val uv1 = if (allocatedWidth > 0 && allocatedHeight > 0) {
                    ImVec2(
                        (source.width.toFloat() / allocatedWidth).coerceIn(0f, 1f),
                        (source.height.toFloat() / allocatedHeight).coerceIn(0f, 1f),
                    )
                } else {
                    ImVec2(1f, 1f)
                }
                ImGui.setCursorScreenPos(ImVec2(fit.x, fit.y))
                ImGui.image(currentTexture, ImVec2(fit.width, fit.height), uv1 = uv1)
            } else {
                drawList.DrawText(
                    ImVec2(start.x + 16f, start.y + 16f),
                    "No video frame — load a media file",
                    0xFF8A8A96.toInt(),
                )
            }
        }
        ImGui.setCursorScreenPos(start)
        ImGui.dummy(ImVec2(videoWidth, videoHeight))

        ImGui.spacing()
        if (ImGui.button(if (paused) "Play" else "Pause", ImVec2(72f * uiScale, 0f))) {
            togglePlayback(paused)
        }
        ImGui.sameLine()
        if (ImGui.button("Stop", ImVec2(72f * uiScale, 0f))) {
            player.stop()
            seekTarget = 0f
        }
        ImGui.sameLine()
        val mute = booleanArrayOf(muted)
        if (ImGui.checkbox("Mute", mute)) {
            player.muted = mute[0]
        }
        ImGui.sameLine(spacing = 10f * uiScale)
        ImGui.text("Vol")
        ImGui.sameLine()
        ImGui.setNextItemWidth(150f * uiScale)
        val volumeValue = floatArrayOf(volume.toFloat())
        if (ImGui.sliderFloat("##volume", volumeValue, 0f, 100f, "%.0f%%")) {
            player.volume = volumeValue[0].roundToInt()
        }

        ImGui.spacing()
        val shownPosition = if (seekDragging) seekTarget else position.toFloat()
        val seekValue = floatArrayOf(shownPosition)
        ImGui.setNextItemWidth((available.x - 170f * uiScale).coerceAtLeast(120f * uiScale))
        if (ImGui.sliderFloat(
                "##seek",
                seekValue,
                0f,
                max(duration.toFloat(), 0.01f),
                "%.1f s",
            )
        ) {
            seekTarget = seekValue[0]
            seekDragging = true
            player.time = (seekTarget * 1000).toLong()
        }
        if (!ImGui.isItemActive()) {
            seekDragging = false
        }
        ImGui.sameLine()
        ImGui.text("${formatTime(shownPosition.toDouble())} / ${formatTime(duration)}")

        lastError?.let { ImGui.textDisabled(it) }

        if (!io.wantTextInput) {
            if (ImGui.isKeyPressed(ImGuiKey.SPACE)) togglePlayback(paused)
            if (ImGui.isKeyPressed(ImGuiKey.LEFT_ARROW)) player.time -= 5_000
            if (ImGui.isKeyPressed(ImGuiKey.RIGHT_ARROW)) player.time += 5_000
            if (ImGui.isKeyPressed(ImGuiKey.UP_ARROW)) player.volume = (volume + 5).coerceAtMost(100)
            if (ImGui.isKeyPressed(ImGuiKey.DOWN_ARROW)) player.volume = (volume - 5).coerceAtLeast(0)
        }

        ImGui.end()
    }

    private fun togglePlayback(paused: Boolean) {
        val ended = player.state == VlcState.Ended ||
            (player.duration > 0 && player.time >= player.duration - 50)
        if (ended) {
            // libVLC ignores play() in the Ended state, and setting time there
            // only moves the reported slider. Stop first so the next play()
            // starts the media from the beginning.
            player.stop()
            seekTarget = 0f
            player.play()
        } else if (paused) {
            player.play()
        } else {
            player.pause()
        }
    }

    private fun sourceSize(): VlcSize {
        // libvlc_video_get_size / HTMLVideoElement report the display size
        // (SAR applied). The vmem surface may be padded to a macroblock
        // boundary, so use the display size for the aspect ratio.
        val reported = runCatching { player.videoSize }.getOrNull()
        if (reported != null && reported.width > 0 && reported.height > 0) return reported
        val width = frameWidth.value
        val height = frameHeight.value
        if (width > 0 && height > 0) return VlcSize(width, height)
        return VlcSize(16, 9)
    }

    private fun fitRect(start: ImVec2, areaWidth: Float, areaHeight: Float, source: VlcSize): FitRect {
        val aspect = source.width.toFloat() / source.height.toFloat()
        var width = areaWidth
        var height = width / aspect
        if (height > areaHeight) {
            height = areaHeight
            width = height * aspect
        }
        return FitRect(
            x = start.x + (areaWidth - width) / 2f,
            y = start.y + (areaHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    private fun updateTexture() {
        val frame = pendingFrame.getAndSet(null) ?: return
        var target = texture
        if (target == null || target.size.x.toInt() != frame.width || target.size.y.toInt() != frame.height) {
            target?.let { backend.unregisterTexture(it) }
            target = renderer.createTexture(
                format = SDLPixelFormat.ARGB8888,
                access = SDLTextureAccess.STREAMING,
                width = frame.width,
                height = frame.height,
            )
            target.scaleMode = SDLScaleMode.LINEAR
            target.blendMode = SDLBlendMode.NONE
            backend.registerTexture(target)
            texture = target
            textureId = target.toImTextureID()
        }
        target.update(rect = null, pixels = frame.pixels, pitch = frame.pitch)
    }

    private fun load(path: String) {
        if (path.isBlank()) return
        try {
            val next = engine.createMedia(path)
            player.stop()
            player.media = next
            media?.close()
            media = next
            lastError = null
            seekTarget = 0f
            player.play()
        } catch (e: Exception) {
            lastError = e.message
            println("load failed: ${e.message}")
        }
    }

    private fun finish(context: cn.enaium.imgui.ImGuiContext) {
        if (finished) return
        finished = true
        if (isWeb) webVideoSurface?.invoke(0f, 0f, 0f, 0f, 1f, 1f, false)
        texture?.let { backend.unregisterTexture(it) }
        texture = null
        textureId = null
        runCatching { player.close() }
        runCatching { media?.close() }
        runCatching { engine.close() }
        backend.close()
        ImGui.destroyContext(context)
        renderer.close()
        window.close()
        SDL.quit()
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
