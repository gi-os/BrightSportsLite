package com.gios.brightsportslite

import com.gios.brightsportslite.data.Refresh
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshTest {

    private val now = 1_800_000_000_000L

    private fun game(state: GameState, start: Long) = Game(
        id = "g", leagueId = "mlb", state = state, startMillis = start, statusDetail = "",
        home = Side("1", "H", "H", "H", null), away = Side("2", "A", "A", "A", null),
    )

    @Test
    fun `never faster than once a minute, even with a game live`() {
        assertEquals(60_000L, Refresh.nextDelay(listOf(game(GameState.LIVE, now - 3_600_000)), now))
        assertTrue(Refresh.LIVE_MILLIS >= 60_000L)
    }

    @Test
    fun `a game about to start counts as live`() {
        assertEquals(Refresh.LIVE_MILLIS, Refresh.nextDelay(listOf(game(GameState.PRE, now + 10 * 60_000)), now))
    }

    @Test
    fun `nothing on means ten minutes`() {
        val games = listOf(game(GameState.FINAL, now - 86_400_000), game(GameState.PRE, now + 86_400_000))
        assertEquals(Refresh.IDLE_MILLIS, Refresh.nextDelay(games, now))
        assertEquals(Refresh.IDLE_MILLIS, Refresh.nextDelay(emptyList(), now))
    }

    @Test
    fun `a cache is fresh only inside the current interval`() {
        val live = listOf(game(GameState.LIVE, now))
        assertTrue(Refresh.isFresh(now - 30_000, live, now))
        assertFalse(Refresh.isFresh(now - 90_000, live, now))
        assertFalse(Refresh.isFresh(0L, emptyList(), now))
    }
}
