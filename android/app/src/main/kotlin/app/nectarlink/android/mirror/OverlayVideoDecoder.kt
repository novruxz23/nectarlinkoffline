// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import app.nectarlink.android.ui.remote.createVideoDecoder
import app.nectarlink.android.ui.remote.extractH264CodecConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes one shared PC window's video into an overlay's surface. Everything runs on one
 * thread of its own, in order; a stream that falls behind is dropped back to the next
 * keyframe rather than shown late.
 */
internal class OverlayVideoDecoder(
    /** The decoder lost its place: ask the PC for a keyframe. */
    private val needKeyframe: () -> Unit,
    /** The first picture is on its way to the screen. */
    private val firstPicture: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "overlay-decoder").apply { isDaemon = true } }
    private val pending = AtomicInteger(0)

    // Only touched on `executor`.
    private var surface: Surface? = null
    private var mime: String? = null
    private var width = 0
    private var height = 0
    private var codec: MediaCodec? = null
    private var waitingForKeyframe = true
    private var shown = false

    /** The surface to draw on, or null when it is gone (the codec is stopped until it is back). */
    fun setSurface(target: Surface?) = run {
        surface = target
        rebuild()
    }

    /** The stream's codec and size (`h264` or `h265`): starts over, as a new stream does. */
    fun configure(codecName: String, w: Int, h: Int) = run {
        mime = when (codecName) {
            "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        width = w
        height = h
        shown = false
        rebuild()
    }

    fun packet(keyframe: Boolean, timeUs: Long, data: ByteArray) {
        // Behind by more than a few packets: what is late is not worth showing.
        if (pending.get() > MAX_PENDING && !keyframe) {
            run { waitingForKeyframe = true }
            needKeyframe()
            return
        }
        pending.incrementAndGet()
        run {
            try {
                feed(keyframe, timeUs, data)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun release() = run {
        stopCodec()
        executor.shutdown()
    }

    /** `block` on the decoder's thread; nothing once it has been released. */
    private fun run(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block) } }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun rebuild() {
        stopCodec()
        val target = surface?.takeIf { it.isValid } ?: return
        val type = mime ?: return
        if (width <= 0 || height <= 0) return
        waitingForKeyframe = true
        val created = runCatching { createVideoDecoder(type, preferSoftware = false) }.getOrNull() ?: return
        val low = MediaFormat.createVideoFormat(type, width, height).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        try {
            created.configure(low, target, null, 0)
        } catch (_: Throwable) {
            runCatching { created.reset() }
            runCatching { created.configure(MediaFormat.createVideoFormat(type, width, height), target, null, 0) }
                .onFailure {
                    runCatching { created.release() }
                    return
                }
        }
        runCatching { created.start() }.onFailure {
            runCatching { created.release() }
            return
        }
        codec = created
        needKeyframe()
    }

    private fun feed(keyframe: Boolean, timeUs: Long, data: ByteArray) {
        val c = codec ?: return
        if (waitingForKeyframe) {
            if (!keyframe) return
            waitingForKeyframe = false
        }
        if (keyframe && mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
            // Some hardware decoders need the parameter sets on their own.
            val split = extractH264CodecConfig(data)
            if (split != null) {
                queue(c, split.first, timeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                drain(c)
                if (!queue(c, split.second, timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)) lost()
                drain(c)
                return
            }
        }
        val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        if (!queue(c, data, timeUs, flags)) lost()
        drain(c)
    }

    private fun lost() {
        waitingForKeyframe = true
        needKeyframe()
    }

    private fun queue(c: MediaCodec, bytes: ByteArray, timeUs: Long, flags: Int): Boolean {
        val index = runCatching { c.dequeueInputBuffer(INPUT_WAIT_US) }.getOrDefault(-1)
        if (index < 0) return false
        val buffer = c.getInputBuffer(index)
        if (buffer == null || bytes.size > buffer.capacity()) {
            runCatching { c.queueInputBuffer(index, 0, 0, 0L, 0) }
            return false
        }
        buffer.clear()
        buffer.put(bytes)
        return runCatching { c.queueInputBuffer(index, 0, bytes.size, timeUs, flags) }.isSuccess
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var wait = OUTPUT_WAIT_US
        while (true) {
            val out = runCatching { c.dequeueOutputBuffer(info, wait) }.getOrDefault(-1)
            if (out >= 0) {
                // Shown as soon as it is ready: a window is for looking at now.
                runCatching { c.releaseOutputBuffer(out, true) }
                if (!shown) {
                    shown = true
                    firstPicture()
                }
                wait = 0
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                wait = 0
            } else {
                break
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 6
        const val INPUT_WAIT_US = 10_000L
        const val OUTPUT_WAIT_US = 4_000L
    }
}
