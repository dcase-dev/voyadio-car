package com.voyadio.automotive.voice

import java.util.Locale

/**
 * Words the talk button knows besides places and kinds of radio (2026-10-21): "저장"·"별 담아" saves (stars, 2026-10-22),
 * "다음"·"다른 나라" drifts on.
 * The car hears Korean, English, French and Spanish (Pleos STT), so these are the four. Countries, languages, kinds and
 * moods are looked up like the car's media search instead (RadioService) — and before "next", so "일본으로 바꿔줘"
 * steers to Japan rather than counting as "change".
 */
object Spoken {
    // "별로" alone means "not much", and "star" is inside "start" — so the star words come in pairs.
    private val SAVE = listOf("저장", "즐겨찾기", "담아", "담기", "save", "favorite", "favourite", "add a star", "star this",
        "enregistre", "favori", "étoile", "guarda", "estrella")
    private val NEXT = listOf("다음", "다른", "넘겨", "next", "another", "skip", "change", "suivant", "autre", "siguiente", "otra", "cambia")

    fun save(words: String) = said(words, SAVE)
    fun next(words: String) = said(words, NEXT)

    private fun said(words: String, keys: List<String>) = words.lowercase(Locale.ROOT).let { text -> keys.any { it in text } }
}
