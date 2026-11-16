@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.vlc

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsExport
import kotlin.js.JsReference
import kotlin.js.get
import kotlin.js.toJsReference
import kotlin.js.unsafeCast

internal actual val webPlatformName: String = "wasmJs"

/**
 * Small JavaScript facade for the Kotlin/Wasm package. Kotlin users should
 * call [Vlc] or [VlcWeb] directly; these exports make the npm artifact usable
 * without a Kotlin toolchain.
 */
@JsExport
fun createEngine(): JsReference<Any> = Vlc.create().toJsReference().toOpaque()

@JsExport
fun createEngineFor(video: JsAny): JsReference<Any> =
    VlcWeb.create(video.unsafeCast<org.w3c.dom.HTMLVideoElement>()).toJsReference().toOpaque()

@JsExport
fun createPlayer(engine: JsReference<Any>): JsReference<Any> =
    (engine.get() as VlcEngine).createMediaPlayer().toJsReference().toOpaque()

@JsExport
fun load(player: JsReference<Any>, media: JsReference<Any>) {
    (player.get() as VlcMediaPlayer).media = (media.get() as VlcMedia)
}

@JsExport
fun createMedia(engine: JsReference<Any>, path: String): JsReference<Any> =
    (engine.get() as VlcEngine).createMedia(path).toJsReference().toOpaque()

@JsExport
fun play(player: JsReference<Any>) {
    (player.get() as VlcMediaPlayer).play()
}

@JsExport
fun pause(player: JsReference<Any>) {
    (player.get() as VlcMediaPlayer).pause()
}

@JsExport
fun stop(player: JsReference<Any>) {
    (player.get() as VlcMediaPlayer).stop()
}

@JsExport
fun seek(player: JsReference<Any>, seconds: Double) {
    (player.get() as VlcMediaPlayer).time = (seconds * 1000).toLong()
}

@JsExport
fun getTime(player: JsReference<Any>): Double = (player.get() as VlcMediaPlayer).time / 1000.0

@JsExport
fun getDuration(player: JsReference<Any>): Double = (player.get() as VlcMediaPlayer).duration / 1000.0

@JsExport
fun isPlaying(player: JsReference<Any>): Boolean = (player.get() as VlcMediaPlayer).isPlaying

@JsExport
fun setVolume(player: JsReference<Any>, volume: Int) {
    (player.get() as VlcMediaPlayer).volume = volume
}

@JsExport
fun closePlayer(player: JsReference<Any>) {
    (player.get() as VlcMediaPlayer).close()
}

@JsExport
fun closeEngine(engine: JsReference<Any>) {
    (engine.get() as VlcEngine).close()
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> JsReference<T>.toOpaque(): JsReference<Any> = this as JsReference<Any>

fun main() = Unit
