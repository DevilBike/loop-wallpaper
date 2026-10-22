package com.devilbike.loopwallpaper

import android.content.SharedPreferences
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** The live wallpaper: plays the current loop over and over, and only while it is visible. */
class LoopWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LoopEngine()

    private inner class LoopEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val prefs = WallpaperStore.prefs(this@LoopWallpaperService)
        private var player: ExoPlayer? = null
        private var hasSurface = false

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
            if (visible && player == null && hasSurface) startPlayer()
            player?.playWhenReady = visible
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == WallpaperStore.KEY_FILE && hasSurface) {
                releasePlayer()
                startPlayer()
            }
        }

        override fun onDestroy() {
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
                prepare()
                playWhenReady = isVisible
            }
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }
    }
}
