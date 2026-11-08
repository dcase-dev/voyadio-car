package com.voyadio.automotive

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/**
 * Shrinks the whole UI on screens smaller than a car display (PO 2026-10-21: "too big on the Galaxy").
 *
 * Every size here is car-sized (76 dp buttons, 24 sp text) for a ~1440×800 dp head unit. A phone in landscape
 * is ~730×330 dp of usable space, so the same layout overflowed and scrolled. Instead of a second set of sizes,
 * the activity runs with a lower density: dp, sp, the globe and every dialog shrink by one factor and the
 * wide layout fits. The user's font size setting still applies on top.
 *
 * A car never shrinks — its glanceable minimums (Design for Driving) are the point. Nothing ever grows.
 */
object UiScale {
    /**
     * Phones and tablets: hide the status bar (PO 2026-10-21) — on a landscape phone it took about 7 % of the height.
     * A swipe from the top shows it for a moment. The car keeps its bars: they belong to the head unit.
     */
    fun hideStatusBar(window: Window, context: Context) {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /** The wide layout's natural size: 32 + 572 (play·next) + 24 + 380 (menu) + 32 wide, menu column + rows tall. */
    const val REF_WIDTH_DP = 1040
    const val REF_HEIGHT_DP = 620
    /** Below this, 24 sp would read as ~12 sp — too small even in the hand. */
    const val MIN = 0.5f

    /** The factor this activity runs at (1 = full size); set by [wrap]. */
    @Volatile var applied = 1f
        private set

    /** [sp] raised just enough that, after the shrink, it still reads as [minSp] — small print stays legible. */
    fun readable(sp: Float, minSp: Float): Float = maxOf(sp, minSp / applied)

    fun factor(widthDp: Int, heightDp: Int, automotive: Boolean): Float {
        if (automotive || widthDp <= 0 || heightDp <= 0) return 1f
        val fit = minOf(widthDp / REF_WIDTH_DP.toFloat(), heightDp / REF_HEIGHT_DP.toFloat())
        return fit.coerceIn(MIN, 1f)
    }

    /** The activity's base context, re-densitied so the layout fits the space left between the system bars. */
    fun wrap(base: Context): Context {
        val config = base.resources.configuration
        val automotive = base.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val (width, height) = usableDp(base, config, automotive)
        val factor = factor(width, height, automotive)
        applied = factor.coerceAtMost(1f)
        if (factor >= 1f) return base
        val scaled = Configuration(config).apply {
            densityDpi = (config.densityDpi * factor).roundToInt()
            screenWidthDp = (config.screenWidthDp / factor).roundToInt()
            screenHeightDp = (config.screenHeightDp / factor).roundToInt()
            smallestScreenWidthDp = (config.smallestScreenWidthDp / factor).roundToInt()
        }
        return base.createConfigurationContext(scaled)
    }

    /**
     * Window size minus the bars that stay on screen and cutouts; the configuration alone counts the bars from Android 15.
     * Off the car the status bar is hidden ([hideStatusBar]), so its height is room for the layout.
     */
    private fun usableDp(base: Context, config: Configuration, automotive: Boolean): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            val metrics = base.getSystemService(WindowManager::class.java).currentWindowMetrics
            val kept = if (automotive) WindowInsets.Type.systemBars() else WindowInsets.Type.navigationBars()
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(kept or WindowInsets.Type.displayCutout())
            val density = base.resources.displayMetrics.density
            val width = (metrics.bounds.width() - bars.left - bars.right) / density
            val height = (metrics.bounds.height() - bars.top - bars.bottom) / density
            if (width > 0 && height > 0) return width.toInt() to height.toInt()
        }
        return config.screenWidthDp to config.screenHeightDp
    }
}
