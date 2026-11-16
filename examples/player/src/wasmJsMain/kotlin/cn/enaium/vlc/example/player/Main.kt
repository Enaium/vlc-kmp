/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.vlc.example.player

import cn.enaium.vlc.VlcWeb
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import kotlin.JsFun
import kotlin.js.unsafeCast

/**
 * Browser entry point: the page initializes SDL/imgui (Emscripten) and then
 * imports this module. The shared PlayerApp draws the ImGui UI while
 * HTMLMediaElement plays the video in a positioned `<video>` element.
 */
fun main() {
    val video = document.getElementById("player")!!.unsafeCast<HTMLVideoElement>()
    val engine = VlcWeb.create(video)
    PlayerApp(
        initialPath = "sample.mp4",
        providedEngine = engine,
        webVideoSurface = { x, y, width, height, displayWidth, displayHeight, visible ->
            placeVideo(x, y, width, height, displayWidth, displayHeight, visible)
        },
    ).run { step, done ->
        fun tick() {
            if (step()) window.setTimeout({ tick(); null }, 16) else done()
        }
        window.setTimeout({ tick(); null }, 16)
    }
}

@JsFun(
    """(x, y, width, height, displayWidth, displayHeight, visible) => {
        const canvas = document.getElementById('canvas');
        const video = document.getElementById('player');
        if (!canvas || !video) return;
        if (!visible || displayWidth <= 0 || displayHeight <= 0) {
            video.style.display = 'none';
            return;
        }
        const rect = canvas.getBoundingClientRect();
        const scaleX = rect.width / displayWidth;
        const scaleY = rect.height / displayHeight;
        video.style.display = 'block';
        video.style.left = (rect.left + x * scaleX) + 'px';
        video.style.top = (rect.top + y * scaleY) + 'px';
        video.style.width = (width * scaleX) + 'px';
        video.style.height = (height * scaleY) + 'px';
    }""",
)
private external fun placeVideo(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    displayWidth: Float,
    displayHeight: Float,
    visible: Boolean,
)
