package com.voyadio.automotive

import java.text.Collator
import java.util.Locale

/**
 * The in-app language list (PO 2026-10-21). Each language is named in itself — "한국어", "English", "日本語" — so a
 * driver stuck in a language they cannot read still finds their own. The region shows only where two entries share
 * a language (中文 简体/繁體, português/português (Brasil)).
 */
internal object AppLanguages {
    fun name(locale: Locale, among: List<Locale>): String {
        val shared = among.count { it.language == locale.language } > 1
        val text = if (shared) locale.getDisplayName(locale) else locale.getDisplayLanguage(locale)
        return text.replaceFirstChar { it.titlecase(locale) }
    }

    /** [supported] sorted by their own names, the way a reader scans a mixed-script list. */
    fun sorted(supported: List<Locale>): List<Locale> {
        val collator = Collator.getInstance(Locale.ROOT)
        return supported.sortedWith { a, b -> collator.compare(name(a, supported), name(b, supported)) }
    }

    /**
     * Which of [supported] [chosen] is: same language and script, the same region when an entry names one
     * (pt-BR ≠ pt), else the entry without a region ("ja-JP" → "ja"). -1 when none fits.
     */
    fun indexOf(chosen: Locale, supported: List<Locale>): Int {
        val candidates = supported.withIndex().filter { (_, s) -> s.language == chosen.language && script(s) == script(chosen) }
        return (candidates.firstOrNull { it.value.country.isNotEmpty() && it.value.country == chosen.country }
            ?: candidates.firstOrNull { it.value.country.isEmpty() }
            ?: candidates.firstOrNull())?.index ?: -1
    }

    /** Chinese written in Taiwan, Hong Kong and Macau is Traditional even when the tag leaves the script out. */
    private fun script(locale: Locale): String = locale.script.ifEmpty {
        if (locale.language == "zh" && locale.country in setOf("TW", "HK", "MO")) "Hant" else ""
    }
}
