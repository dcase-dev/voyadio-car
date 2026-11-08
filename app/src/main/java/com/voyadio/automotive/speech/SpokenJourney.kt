package com.voyadio.automotive.speech

/**
 * The voice guide's sentence, built from the same localized phrases the screens use (50 languages, no new
 * translations): "Namibia. Broadcasting from Windhoek. 10,499 km away, about 10 days 22 hours by car."
 * The screens separate parts with "·"; spoken, that becomes a comma. Parked, the place's hour follows ([clock]); on
 * the road a border crossing or the arrival comes first ([intro], "독일에 들어왔어요").
 */
object SpokenJourney {
    fun text(country: String, from: String?, detail: String?, clock: String? = null, intro: String? = null): String =
        listOf(intro, country, from, detail, clock)
            .map { it?.replace(" · ", ", ")?.trim()?.trimEnd('.', '。', '।').orEmpty() }
            .filter { it.isNotEmpty() }
            .joinToString(". ")
}
