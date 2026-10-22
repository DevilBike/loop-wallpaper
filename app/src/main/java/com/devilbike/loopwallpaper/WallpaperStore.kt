package com.devilbike.loopwallpaper

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Keeps track of which loop the live wallpaper plays. */
object WallpaperStore {
    const val KEY_FILE = "file"
    private const val PREFS = "wallpaper"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(context: Context) = File(context.filesDir, "wallpaper").apply { mkdirs() }

    fun currentFile(context: Context): File? =
        prefs(context).getString(KEY_FILE, null)?.let { File(dir(context), it) }?.takeIf { it.exists() }

    /** Copies [loop] into app storage and makes it the wallpaper. Running wallpapers reload automatically. */
    fun install(context: Context, loop: File) {
        val dir = dir(context)
        val name = "loop_${System.currentTimeMillis()}.mp4"
        val temp = File(dir, "$name.tmp")
        loop.copyTo(temp, overwrite = true)
        check(temp.renameTo(File(dir, name))) { "Couldn't save the wallpaper file." }
        prefs(context).edit().putString(KEY_FILE, name).commit()
        dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
    }
}
