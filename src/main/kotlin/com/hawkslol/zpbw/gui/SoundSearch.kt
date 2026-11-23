package com.hawkslol.zpbw.gui

private val soundSeparators = Regex("[\\s._:/-]+")

internal fun searchSounds(catalog: List<String>, search: String): List<String> {
    val query = search.trim().lowercase()
    val terms = query.split(soundSeparators).filter(String::isNotEmpty)
    if (terms.isEmpty()) return catalog

    val ranked = catalog.mapNotNull { id ->
        val words = id.split(soundSeparators)
        val rank = when {
            query == id || query == id.substringAfter(':') -> 0
            terms.all { it in words } -> 1
            terms.all { term -> words.any { it.startsWith(term) } } -> 2
            terms.all { it in id } -> 3
            else -> return@mapNotNull null
        }
        rank to id
    }
    return ranked.sortedBy { it.first }.map { it.second }
}
