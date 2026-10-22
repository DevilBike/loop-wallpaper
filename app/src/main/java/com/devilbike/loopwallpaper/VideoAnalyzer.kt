package com.devilbike.loopwallpaper

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** An error with a message that is safe to show to the user. */
class LoopException(message: String) : Exception(message)

/**
 * Tiny grayscale versions of every frame in a video, used to compare frames quickly.
 * [full] is [FULL_W]x[FULL_H], [small] is [SMALL_W]x[SMALL_H]; both hold unsigned bytes.
 */
class VideoThumbs(
    val full: Array<ByteArray>,
    val small: Array<ByteArray>,
    val ptsUs: LongArray,
    val durationUs: Long,
    val fps: Float,
    val width: Int,
    val height: Int,
) {
    val frameCount get() = ptsUs.size
    val frameDurationUs get() = (1_000_000f / fps).toLong()

    companion object {
        const val FULL_W = 64
        const val FULL_H = 36
        const val SMALL_W = 16
        const val SMALL_H = 9
        const val MAX_FRAMES = 20_000
    }
}

object VideoAnalyzer {

    fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw LoopException("That file doesn't contain a video track.")
    }

    /** Decodes the whole video and returns a thumbnail per frame. [onProgress] gets 0..1. */
    fun analyze(file: File, onProgress: (Float) -> Unit): VideoThumbs {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = findVideoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val frames = ArrayList<Pair<Long, ByteArray>>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleCount = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    idleCount = 0
                    val pts = info.presentationTimeUs
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        codec.getOutputImage(outIndex)?.use { image ->
                            frames.add(pts to thumbnail(image))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (frames.size > VideoThumbs.MAX_FRAMES) {
                        throw LoopException("This video is too long. Trim it to a few minutes first.")
                    }
                    if (durationUs > 0) onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (eos) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    if (++idleCount > 500) break
                }
            }

            if (frames.size < 4) throw LoopException("Couldn't read frames from this video.")
            frames.sortBy { it.first }
            val pts = LongArray(frames.size) { frames[it].first }
            val full = Array(frames.size) { frames[it].second }
            val small = Array(frames.size) { downscale(full[it]) }
            val span = pts.last() - pts.first()
            val fps = if (span > 0) (frames.size - 1) * 1_000_000f / span else 30f
            val total = maxOf(durationUs, pts.last() + (1_000_000f / fps).toLong())

            var width = format.getInteger(MediaFormat.KEY_WIDTH)
            var height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                if (rotation == 90 || rotation == 270) width = height.also { height = width }
            }
            return VideoThumbs(full, small, pts, total, fps, width, height)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Box-averages the luma plane into a FULL_W x FULL_H grid, sampling every other pixel. */
    private fun thumbnail(image: Image): ByteArray {
        val tw = VideoThumbs.FULL_W
        val th = VideoThumbs.FULL_H
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // 10-bit formats store little-endian 16-bit samples; the high byte carries the 8 most significant bits.
        val byteOffset = if (pixelStride == 2 && image.format == ImageFormat.YCBCR_P010) 1 else 0

        val sums = IntArray(tw * th)
        val counts = IntArray(tw * th)
        val txOf = IntArray(cw) { it * tw / cw }
        val rowLength = (cw - 1) * pixelStride + 1 + byteOffset
        val row = ByteArray(rowLength)

        var y = 0
        while (y < ch) {
            val ty = y * th / ch
            val start = (crop.top + y) * rowStride + crop.left * pixelStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            val len = minOf(rowLength, buffer.limit() - start)
            buffer.get(row, 0, len)
            val base = ty * tw
            var x = 0
            while (x < cw) {
                val idx = x * pixelStride + byteOffset
                if (idx >= len) break
                val cell = base + txOf[x]
                sums[cell] += row[idx].toInt() and 0xFF
                counts[cell]++
                x += 2
            }
            y += 2
        }
        return ByteArray(tw * th) { if (counts[it] > 0) (sums[it] / counts[it]).toByte() else 0 }
    }

    private fun downscale(full: ByteArray): ByteArray {
        val fx = VideoThumbs.FULL_W / VideoThumbs.SMALL_W
        val fy = VideoThumbs.FULL_H / VideoThumbs.SMALL_H
        val out = ByteArray(VideoThumbs.SMALL_W * VideoThumbs.SMALL_H)
        for (sy in 0 until VideoThumbs.SMALL_H) {
            for (sx in 0 until VideoThumbs.SMALL_W) {
                var sum = 0
                for (dy in 0 until fy) {
                    val rowBase = (sy * fy + dy) * VideoThumbs.FULL_W + sx * fx
                    for (dx in 0 until fx) sum += full[rowBase + dx].toInt() and 0xFF
                }
                out[sy * VideoThumbs.SMALL_W + sx] = (sum / (fx * fy)).toByte()
            }
        }
        return out
    }
}
