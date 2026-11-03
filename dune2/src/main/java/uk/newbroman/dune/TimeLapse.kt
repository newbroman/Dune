package uk.newbroman.dune

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time-lapse: one frame every few seconds, encoded straight into an H.264 video at 30 frames a second, so a
 * long session needs no space beyond the video itself. All calls must come from one thread.
 */
class TimeLapseEncoder private constructor(val file: File, val width: Int, val height: Int, private val codec: MediaCodec, private val surface: Surface) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var written = 0
    var frames = 0; private set
    private val info = MediaCodec.BufferInfo()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    companion object {
        const val FPS = 30
        /** An encoder for frames of this shape (portrait or landscape), trying smaller sizes if the phone's encoder refuses. */
        fun open(ctx: Context, portrait: Boolean, rotationHint: Int = 0): TimeLapseEncoder {
            val sizes = listOf(1440 to 1080, 1280 to 960, 960 to 720)
            var last: Throwable? = null
            for ((l, s) in sizes) {
                val w = if (portrait) s else l; val h = if (portrait) l else s
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, w * h * 8)
                        setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = codec.createInputSurface()
                    codec.start()
                    val file = File(ctx.cacheDir, "lapse.mp4").apply { delete() }
                    return TimeLapseEncoder(file, w, h, codec, surface)
                } catch (t: Throwable) { last = t; runCatching { codec.release() } }
            }
            throw IllegalStateException("No video encoder for time-lapse", last)
        }
    }

    /** Adds one frame, scaled to fill the video (cropping the edges if the shapes differ). */
    fun add(bm: Bitmap) {
        val c = if (Build.VERSION.SDK_INT >= 23) surface.lockHardwareCanvas() else surface.lockCanvas(null)
        try {
            val s = maxOf(width / bm.width.toFloat(), height / bm.height.toFloat())
            val sw = (width / s).toInt(); val sh = (height / s).toInt()
            val sx = (bm.width - sw) / 2; val sy = (bm.height - sh) / 2
            c.drawBitmap(bm, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, width, height), paint)
        } finally { surface.unlockCanvasAndPost(c) }
        frames++
        drain(false)
    }

    /** Ends the video; returns the file (null if nothing was recorded). */
    fun finish(): File? {
        runCatching { codec.signalEndOfInputStream(); drain(true) }
        runCatching { codec.stop() }; codec.release(); surface.release()
        val ok = started && written > 0
        runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        return if (ok) file else { file.delete(); null }
    }
    fun cancel() { runCatching { codec.stop() }; runCatching { codec.release() }; surface.release(); runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }; file.delete() }

    private fun drain(end: Boolean) {
        val until = System.currentTimeMillis() + if (end) 5000 else 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 20_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || System.currentTimeMillis() > until) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && started && !config && info.size > 0) {
                        // frames go out at 30 a second, whatever the real gap between them was
                        info.presentationTimeUs = written * 1_000_000L / FPS
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info); written++
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(i, false)
                    if (eos) return
                }
            }
        }
    }
}

object TimeLapse {
    val INTERVALS = listOf(1, 2, 5, 10, 30, 60)

    /** Copies the finished video into Movies/Dune. */
    fun save(ctx: Context, f: File): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Dune_LAPSE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Captures.movies)
        }
        val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        f.delete()
        return u
    }
}
