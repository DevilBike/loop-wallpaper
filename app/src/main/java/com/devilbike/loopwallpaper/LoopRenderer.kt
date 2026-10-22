package com.devilbike.loopwallpaper

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes the loop [startUs, endUs) of [source] to [output] as a silent MP4.
 *
 * With a crossfade of length c, the output covers source time [start + c, end). During the last c
 * of the output, the frames are blended into source [start, start + c), so the final frame flows
 * straight into the first one.
 */
class LoopRenderer(
    private val source: File,
    private val output: File,
    private val startUs: Long,
    private val endUs: Long,
    private val crossfadeUs: Long,
    private val fps: Float,
) {
    fun render(onProgress: (Float) -> Unit) {
        val probe = MediaExtractor()
        val format: MediaFormat
        try {
            probe.setDataSource(source.absolutePath)
            format = probe.getTrackFormat(VideoAnalyzer.findVideoTrack(probe))
        } finally {
            probe.release()
        }
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val (srcW, srcH) = displaySize(format)

        val callbackThread = HandlerThread("LoopRendererFrames").apply { start() }
        val handler = Handler(callbackThread.looper)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: Egl? = null
        var main: FrameSource? = null
        var partner: FrameSource? = null
        try {
            val (enc, w, h) = createEncoder(srcW, srcH)
            encoder = enc
            val inputSurface = enc.createInputSurface()
            enc.start()

            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val sink = EncoderSink(enc, muxer)

            egl = Egl(inputSurface)
            val blender = Blender()
            val halfFrame = (500_000f / fps).toLong()
            val fade = crossfadeUs.coerceIn(0, (endUs - startUs) / 2)
            val outStart = startUs + fade

            main = FrameSource(source, blender.textureA, handler, outStart - halfFrame)
            if (fade > 0) partner = FrameSource(source, blender.textureB, handler, startUs - halfFrame)

            var pts = main.nextFrame(outStart - halfFrame)
                ?: throw LoopException("Couldn't read the start of the loop.")
            GLES20.glViewport(0, 0, w, h)
            while (pts < endUs - halfFrame) {
                var mix = 0f
                if (fade > 0 && pts >= endUs - fade - halfFrame) {
                    val partnerTarget = pts - (endUs - startUs) + fade
                    partner?.nextFrame(partnerTarget - halfFrame)
                    mix = ((pts - (endUs - fade)).toFloat() / fade).coerceIn(0f, 1f)
                }
                blender.draw(main.transform, partner?.transform ?: main.transform, mix)
                egl.swap((pts - outStart) * 1000)
                sink.drain(false)
                onProgress(((pts - outStart).toFloat() / (endUs - outStart)).coerceIn(0f, 1f))
                pts = main.nextFrame(pts + 1) ?: break
            }
            sink.drain(true)
            sink.finish()
        } finally {
            main?.release()
            partner?.release()
            egl?.release()
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            encoder?.release()
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            callbackThread.quitSafely()
        }
    }

    private fun displaySize(format: MediaFormat): Pair<Int, Int> {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        return w to h
    }

    /** Creates an H.264 encoder at the largest supported size up to 1080p with the source's aspect ratio. */
    private fun createEncoder(srcW: Int, srcH: Int): Triple<MediaCodec, Int, Int> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            ?.getCapabilitiesForType(mime)?.videoCapabilities

        val longSide = max(srcW, srcH)
        var lastError: Exception? = null
        for (limit in intArrayOf(1920, 1280, 960, 640)) {
            val scale = minOf(1f, limit.toFloat() / longSide, (limit * 9f / 16f) / minOf(srcW, srcH))
            val w = even(srcW * scale)
            val h = even(srcH * scale)
            if (caps != null && !caps.isSizeSupported(w, h)) continue
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.25f).toInt().coerceIn(2_000_000, 24_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Triple(codec, w, h)
            } catch (e: Exception) {
                codec.release()
                lastError = e
            }
        }
        throw LoopException("This phone's video encoder couldn't handle the video size. ${lastError?.message ?: ""}".trim())
    }

    private fun even(v: Float) = max(2, (v / 2f).roundToInt() * 2)
}

/** Copies encoded output from [encoder] into [muxer]. */
private class EncoderSink(private val encoder: MediaCodec, private val muxer: MediaMuxer) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var samples = 0

    fun drain(endOfStream: Boolean) {
        if (endOfStream) encoder.signalEndOfInputStream()
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++waits > 500) throw LoopException("The video encoder stopped responding.")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        samples++
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish() {
        if (!started || samples == 0) throw LoopException("No frames were written.")
        muxer.stop()
    }
}

/** Decodes a video into an OpenGL texture one frame at a time. */
private class FrameSource(file: File, textureId: Int, handler: Handler, seekUs: Long) {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val surfaceTexture = SurfaceTexture(textureId)
    private val surface = Surface(surfaceTexture)
    private val frameReady = ConditionVariable()
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    val transform = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener({ frameReady.open() }, handler)
        extractor.setDataSource(file.absolutePath)
        val track = VideoAnalyzer.findVideoTrack(extractor)
        extractor.selectTrack(track)
        extractor.seekTo(max(0, seekUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val format = extractor.getTrackFormat(track)
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    /** Shows the first frame at or after [minPtsUs] in the texture and returns its time, or null at the end. */
    fun nextFrame(minPtsUs: Long): Long? {
        var idle = 0
        while (!outputDone) {
            feedInput()
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index < 0) {
                if (++idle > 500) throw LoopException("The video decoder stopped responding.")
                continue
            }
            idle = 0
            val pts = info.presentationTimeUs
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            val show = info.size > 0 && pts >= minPtsUs
            decoder.releaseOutputBuffer(index, show)
            if (show) {
                if (!frameReady.block(3000)) throw LoopException("Timed out waiting for a video frame.")
                frameReady.close()
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(transform)
                return pts
            }
        }
        return null
    }

    private fun feedInput() {
        while (!inputDone) {
            val index = decoder.dequeueInputBuffer(0)
            if (index < 0) return
            val buffer = decoder.getInputBuffer(index)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
    }

    fun release() {
        try {
            decoder.stop()
        } catch (_: Exception) {
        }
        decoder.release()
        extractor.release()
        surface.release()
        surfaceTexture.release()
    }
}

/** An EGL context that renders into an encoder's input surface. */
private class Egl(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No suitable EGL config"
        }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun swap(presentationTimeNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Draws a full-screen blend of two external (video) textures. */
private class Blender {
    val textureA: Int
    val textureB: Int
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexMatrixA: Int
    private val uTexMatrixB: Int
    private val uMix: Int
    private val vertices: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        for (id in textures) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        textureA = textures[0]
        textureB = textures[1]

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixA = GLES20.glGetUniformLocation(program, "uTexMatrixA")
        uTexMatrixB = GLES20.glGetUniformLocation(program, "uTexMatrixB")
        uMix = GLES20.glGetUniformLocation(program, "uMix")
    }

    fun draw(matrixA: FloatArray, matrixB: FloatArray, mix: Float) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureA)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTextureA"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureB)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTextureB"), 1)

        GLES20.glUniformMatrix4fv(uTexMatrixA, 1, false, matrixA, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixB, 1, false, matrixB, 0)
        GLES20.glUniform1f(uMix, mix)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrixA;
            uniform mat4 uTexMatrixB;
            varying vec2 vTexA;
            varying vec2 vTexB;
            void main() {
                gl_Position = aPosition;
                vTexA = (uTexMatrixA * aTexCoord).xy;
                vTexB = (uTexMatrixB * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexA;
            varying vec2 vTexB;
            uniform samplerExternalOES sTextureA;
            uniform samplerExternalOES sTextureB;
            uniform float uMix;
            void main() {
                gl_FragColor = mix(texture2D(sTextureA, vTexA), texture2D(sTextureB, vTexB), uMix);
            }
        """

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
