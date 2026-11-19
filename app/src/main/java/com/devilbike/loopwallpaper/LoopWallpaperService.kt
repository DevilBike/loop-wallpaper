package com.devilbike.loopwallpaper

import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** The live wallpaper: plays the current loop over and over, and only while it is visible. */
class LoopWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LoopEngine()

    private inner class LoopEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val prefs = WallpaperStore.prefs(this@LoopWallpaperService)
        private var player: ExoPlayer? = null
        private var hasSurface = false
        private val handler = Handler(Looper.getMainLooper())
        private var lastPosition = -1L
        private var stalledChecks = 0

        // Free the video decoder shortly after the wallpaper is hidden, so other apps can't
        // reclaim it out from under us (which used to leave the wallpaper frozen).
        private val releaseWhenHidden = Runnable { if (!isVisible) releasePlayer() }

        // While visible, restart the player if it has errored out or stopped advancing.
        private val watchdog = object : Runnable {
            override fun run() {
                val p = player
                if (!isVisible || !hasSurface) return
                if (p == null || p.playbackState == Player.STATE_IDLE) {
                    startPlayer()
                } else if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
                    val pos = p.currentPosition
                    stalledChecks = if (pos == lastPosition) stalledChecks + 1 else 0
                    lastPosition = pos
                    if (stalledChecks >= 2) startPlayer()
                }
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            hasSurface = true
            startPlayer()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            hasSurface = false
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            handler.removeCallbacks(releaseWhenHidden)
            handler.removeCallbacks(watchdog)
            if (visible) {
                if (player == null && hasSurface) startPlayer()
                player?.playWhenReady = true
                resetWatchdog()
                handler.postDelayed(watchdog, WATCHDOG_MS)
            } else {
                player?.playWhenReady = false
                handler.postDelayed(releaseWhenHidden, RELEASE_DELAY_MS)
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == WallpaperStore.KEY_FILE && hasSurface) {
                releasePlayer()
                startPlayer()
            }
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            releasePlayer()
            super.onDestroy()
        }

        private fun startPlayer() {
            releasePlayer()
            val file = WallpaperStore.currentFile(this@LoopWallpaperService) ?: return
            player = ExoPlayer.Builder(this@LoopWallpaperService).build().apply {
                setVideoSurface(surfaceHolder.surface)
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Decoder lost or file hiccup: rebuild instead of sitting on a frozen frame.
                        handler.postDelayed({ if (hasSurface && isVisible) startPlayer() }, 500)
                    }
                })
                prepare()
                playWhenReady = isVisible
            }
            resetWatchdog()
        }

        private fun resetWatchdog() {
            lastPosition = -1L
            stalledChecks = 0
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }
    }

    private companion object {
        const val WATCHDOG_MS = 1500L
        const val RELEASE_DELAY_MS = 3000L
    }
}
