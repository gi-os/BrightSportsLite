package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState

/**
 * How often the scores screen asks again while it is open.
 *
 * Light's guidelines ask tools not to be built for compulsive re-checking, so the floor
 * is a minute even with a game in progress, and with nothing live the screen waits ten.
 * Nothing refreshes while the screen is not showing; the background job only keeps the
 * cache warm so the screen opens on recent numbers.
 */
object Refresh {

    const val LIVE_MILLIS = 60_000L
    const val IDLE_MILLIS = 10 * 60_000L

    /** A game this close to its start counts as about to be live. */
    const val LEAD_MILLIS = 15 * 60_000L

    /** A tap on refresh sooner than this after the last fetch is not sent. */
    const val MANUAL_MIN_MILLIS = 20_000L

    /** Background cache refresh. WorkManager will not go below fifteen minutes anyway. */
    const val BACKGROUND_MINUTES = 30L

    fun nextDelay(games: List<Game>, nowMillis: Long): Long {
        val busy = games.any {
            it.state == GameState.LIVE ||
                (it.state == GameState.PRE && it.startMillis - nowMillis in -LEAD_MILLIS * 4..LEAD_MILLIS)
        }
        return if (busy) LIVE_MILLIS else IDLE_MILLIS
    }

    /** Whether a cache written at [fetchedAt] is still good enough to show without asking. */
    fun isFresh(fetchedAt: Long, games: List<Game>, nowMillis: Long): Boolean =
        fetchedAt > 0 && nowMillis - fetchedAt < nextDelay(games, nowMillis)
}
