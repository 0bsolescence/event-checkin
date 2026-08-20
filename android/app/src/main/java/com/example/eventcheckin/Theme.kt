package com.example.eventcheckin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Branding loaded at startup from `theme/theme.json` inside the app's private
 * files directory — the Android twin of the Windows `Theme.cs`, same schema and
 * same contract. If the file is absent or invalid the app runs with the neutral
 * defaults below; no organization branding is compiled into the APK. Schema:
 * appTitle, primary, accent, background, foreground (hex colors), optional
 * logoPath — a relative path resolved inside the theme directory only.
 *
 * Two deliberate divergences from the Windows twin, both because the theme
 * arrives here through the Storage Access Framework rather than a file copy:
 * an imported logo is always written as `logo.png`, and that file is used when
 * logoPath is absent or does not resolve. Colors accept hex only (#RGB,
 * #RRGGBB, #AARRGGBB) — WPF's named colors have no equivalent here.
 */
class Theme private constructor(
    val appTitle: String,
    val primary: Int,
    val accent: Int,
    val background: Int,
    val foreground: Int,
    /** Black or white, whichever reads against [primary]. */
    val onPrimary: Int,
    val logo: Bitmap?,
) {
    companion object {
        const val DIR = "theme"
        const val THEME_FILE = "theme.json"
        const val LOGO_FILE = "logo.png"

        /** A theme file is a handful of hex strings; anything larger is not one. */
        const val MAX_THEME_BYTES = 64 * 1024
        const val MAX_LOGO_BYTES = 4 * 1024 * 1024

        const val DEFAULT_TITLE = "Event Check-In"
        const val DEFAULT_PRIMARY = 0xFF3F3F46.toInt()
        const val DEFAULT_ACCENT = 0xFFE0E0E0.toInt()
        const val DEFAULT_BACKGROUND = 0xFFFFFFFF.toInt()
        const val DEFAULT_FOREGROUND = 0xFF1A1A1A.toInt()

        fun dir(context: Context): File = File(context.filesDir, DIR)

        fun neutral(): Theme = Theme(
            DEFAULT_TITLE, DEFAULT_PRIMARY, DEFAULT_ACCENT, DEFAULT_BACKGROUND,
            DEFAULT_FOREGROUND, ThemeColors.onColor(DEFAULT_PRIMARY), null)

        fun load(context: Context): Theme {
            val d = dir(context)
            var title = DEFAULT_TITLE
            var primary = DEFAULT_PRIMARY
            var accent = DEFAULT_ACCENT
            var background = DEFAULT_BACKGROUND
            var foreground = DEFAULT_FOREGROUND
            var logoPath: String? = null
            try {
                val f = File(d, THEME_FILE)
                if (f.isFile && f.length() <= MAX_THEME_BYTES) {
                    val o = JSONObject(f.readText())
                    // JSON null, missing or unparseable falls back per-field, not whole-theme.
                    o.optString("appTitle").trim().takeIf { it.isNotEmpty() }?.let { title = it }
                    primary = ThemeColors.parse(o.optString("primary"), primary)
                    accent = ThemeColors.parse(o.optString("accent"), accent)
                    background = ThemeColors.parse(o.optString("background"), background)
                    foreground = ThemeColors.parse(o.optString("foreground"), foreground)
                    logoPath = o.optString("logoPath").trim().takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                // A broken theme file must not take the kiosk down — run neutral instead.
                return neutral()
            }
            return Theme(title, primary, accent, background, foreground,
                ThemeColors.onColor(primary), loadLogo(resolveLogo(d, logoPath)))
        }

        /** Only a file inside the theme directory qualifies; a rooted path or a
         *  `..` traversal is rejected before any read. Falls back to the file the
         *  logo importer writes. */
        fun resolveLogo(dir: File, logoPath: String?): File? = try {
            val base = dir.canonicalFile
            val named = logoPath?.trim()?.takeIf {
                it.isNotEmpty() && !it.startsWith("/") && !it.startsWith("\\") && !it.contains(":")
            }?.let { File(base, it).canonicalFile }
            when {
                named != null && named.path.startsWith(base.path + File.separator) && named.isFile -> named
                File(base, LOGO_FILE).isFile -> File(base, LOGO_FILE)
                else -> null
            }
        } catch (_: Exception) {
            null // any path weirdness degrades to no logo
        }

        private fun loadLogo(file: File?): Bitmap? = try {
            // Corrupt or oversized image: run without a logo rather than crash.
            if (file != null && file.length() <= MAX_LOGO_BYTES)
                BitmapFactory.decodeFile(file.path) else null
        } catch (_: Exception) {
            null
        }

        /** Writes an already-validated theme file. */
        fun writeThemeJson(context: Context, bytes: ByteArray) {
            val d = dir(context)
            d.mkdirs()
            File(d, THEME_FILE).writeBytes(bytes)
        }

        /** Writes an already-validated image as the theme's logo. */
        fun writeLogo(context: Context, bytes: ByteArray) {
            val d = dir(context)
            d.mkdirs()
            File(d, LOGO_FILE).writeBytes(bytes)
        }

        /** Back to the compiled-in neutral defaults: the whole theme directory goes. */
        fun reset(context: Context) {
            dir(context).deleteRecursively()
        }
    }
}

/**
 * Pure color helpers — no Android types, so the parsing and the on-primary
 * luminance rule are unit testable off-device.
 */
object ThemeColors {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun parse(hex: String?, fallback: Int): Int = parseOrNull(hex) ?: fallback

    /** Accepts #RGB, #RRGGBB and #AARRGGBB, with or without the leading #. */
    fun parseOrNull(hex: String?): Int? {
        val s = hex?.trim()?.removePrefix("#")?.uppercase() ?: return null
        if (s.isEmpty() || s.any { it !in "0123456789ABCDEF" }) return null
        val rgb = when (s.length) {
            3 -> s.map { "$it$it" }.joinToString("")
            6 -> s
            8 -> s.substring(2)
            else -> return null
        }
        return try {
            val alpha = if (s.length == 8) s.substring(0, 2).toInt(16) else 0xFF
            (alpha shl 24) or rgb.toInt(16)
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Black or white against [color], by the same weighted luminance the
     *  Windows Theme.cs uses. */
    fun onColor(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return if ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5) BLACK else WHITE
    }
}
