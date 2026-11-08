package com.voyadio.automotive

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * The broadcasting country's flag beside its name (PO 2026-10-21), drawn from the system emoji font —
 * the phone app does the same (lib/services/flag_art.dart). No bundled images, no licence to track.
 * The flag is a picture, not text, so it never mixes into the line's font or shadow.
 */
internal object Flags {
    private val cache = HashMap<String, Bitmap?>()

    /** The regional-indicator pair for a two-letter country code ("KR" → 🇰🇷), or null. */
    fun emoji(code: String): String? {
        val c = code.trim().uppercase(Locale.ROOT)
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        return c.map { String(Character.toChars(REGIONAL_A + (it - 'A'))) }.joinToString("")
    }

    /** The flag [heightPx] tall, or null when the font has no flag for [code] (it would draw two boxed letters). */
    fun drawable(resources: Resources, code: String, heightPx: Int): Drawable? {
        val key = code.uppercase(Locale.ROOT)
        val bitmap = synchronized(cache) { cache.getOrPut(key) { render(key) } } ?: return null
        return BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, heightPx * bitmap.width / bitmap.height, heightPx) }
    }

    private fun render(code: String): Bitmap? {
        val flag = emoji(code) ?: return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = RENDER_PX }
        if (!paint.hasGlyph(flag)) return null
        val bounds = Rect().also { paint.getTextBounds(flag, 0, flag.length, it) }
        if (bounds.isEmpty) return null
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(flag, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
        return bitmap
    }

    private const val REGIONAL_A = 0x1F1E6
    private const val RENDER_PX = 96f
}

/** The one line under the station name: "country · capital", or just the country where the map has no capital. */
internal object PlaceLine {
    /** [atlasName] is the map's own (English) country name, which stands in for a missing capital. */
    fun of(country: String, capitalName: String?, atlasName: String?): String =
        if (capitalName.isNullOrBlank() || capitalName == atlasName || capitalName == country) country
        else "$country · $capitalName"
}
