package com.landenlabs.homewx.ui

import kotlin.random.Random

/**
 * Chooses one item from a list per [key] and moves to the next item once an hour. The first pick
 * for a key starts at a random item, so a restart doesn't always begin with the same one; after
 * that the same item is returned for the rest of the hour, then the next (wrapping). Several
 * hours passing with no call still moves only one step, so a long idle doesn't skip items.
 */
class HourlyRotation<K>(private val randomStart: (size: Int) -> Int = { Random.nextInt(it) }) {

    private val position = mutableMapOf<K, Int>()
    private val hourShown = mutableMapOf<K, Long>()

    /** The item for [key] at [nowMillis], or null if [items] is empty. */
    @Synchronized
    fun <T> pick(key: K, items: List<T>, nowMillis: Long): T? {
        if (items.isEmpty()) return null
        val hour = nowMillis / HOUR_MILLIS
        val current = position[key]
        val next = when {
            current == null -> randomStart(items.size)
            hour != hourShown[key] -> (current + 1) % items.size
            else -> current % items.size // list may have shrunk since the last pick
        }
        position[key] = next
        hourShown[key] = hour
        return items[next]
    }

    private companion object {
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}
