package com.gios.brightsportslite

import com.gios.brightsportslite.data.Iso
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Moment
import com.gios.brightsportslite.model.ScoringPlay
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.model.Situation
import com.gios.brightsportslite.model.SportKind
import com.gios.brightsportslite.text.CardText
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardTextTest {

    private val zone = ZoneId.of("America/New_York")
    private val sea = Side("26", "Seattle Seahawks", "Seahawks", "SEA", 14)
    private val ne = Side("17", "New England Patriots", "Patriots", "NE", 7)

    private fun nfl(
        state: GameState = GameState.LIVE,
        clock: String? = "3:24",
        period: Int = 2,
        detail: String = "3:24 - 2nd",
        statusName: String? = null,
        situation: Situation? = null,
    ) = Game(
        id = "g", leagueId = "nfl", state = state,
        startMillis = Iso.millis("2026-09-13T17:00Z"),
        statusDetail = detail, period = period, clock = clock, statusName = statusName,
        home = sea, away = ne, situation = situation,
    )

    @Test
    fun `a running clock reads as period and time`() {
        assertEquals("NFL · Q2 3:24", CardText.statusLine(nfl(), zone))
    }

    @Test
    fun `halftime is named, not shown as a frozen clock`() {
        val g = nfl(clock = "0:00", detail = "Halftime", statusName = "STATUS_HALFTIME")
        assertEquals("NFL · HALFTIME", CardText.statusLine(g, zone))
    }

    @Test
    fun `a pre-game row shows the local kickoff`() {
        assertEquals("NFL · SUN 1:00 PM", CardText.statusLine(nfl(state = GameState.PRE), zone))
    }

    @Test
    fun `a final keeps the provider's wording`() {
        val g = nfl(state = GameState.FINAL, detail = "Final/OT")
        assertEquals("NFL · FINAL/OT", CardText.statusLine(g, zone))
    }

    @Test
    fun `an event title takes the league's place`() {
        val g = nfl(state = GameState.PRE).copy(eventTitle = "Super Bowl LX")
        assertTrue(CardText.statusLine(g, zone).startsWith("SUPER BOWL LX · "))
    }

    @Test
    fun `period labels follow the sport, and overtime is not a fifth quarter`() {
        assertEquals("Q4", CardText.periodLabel(SportKind.FOOTBALL, 4))
        assertEquals("OT", CardText.periodLabel(SportKind.FOOTBALL, 5))
        assertEquals("2OT", CardText.periodLabel(SportKind.BASKETBALL, 6))
        assertEquals("P3", CardText.periodLabel(SportKind.HOCKEY, 3))
        assertEquals("OT", CardText.periodLabel(SportKind.HOCKEY, 4))
        assertEquals("H2", CardText.periodLabel(SportKind.SOCCER, 2))
        assertEquals("11th", CardText.periodLabel(SportKind.BASEBALL, 11))
        assertEquals("", CardText.periodLabel(SportKind.BASEBALL, 0))
    }

    @Test
    fun `the third line is the count and the runners in baseball`() {
        val g = nfl().copy(
            leagueId = "mlb",
            situation = Situation(balls = 2, strikes = 1, outs = 1, onFirst = true, onThird = true),
        )
        assertEquals("2-1 · 1 out · Runners on 1st and 3rd", CardText.thirdLine(g))
    }

    @Test
    fun `the third line is down and distance in football, with the red zone`() {
        val g = nfl(situation = Situation(downDistance = "2nd & 7 at NE 16", isRedZone = true))
        assertEquals("2nd & 7 at NE 16 · Red zone", CardText.thirdLine(g))
    }

    @Test
    fun `before the game the third line is the line and the weather`() {
        val g = nfl(state = GameState.PRE).copy(odds = "SEA -3", overUnder = "44.5", weather = "75° Sunny")
        assertEquals("SEA -3 · O/U 44.5 · 75° Sunny", CardText.thirdLine(g))
    }

    @Test
    fun `a postponed game has nothing under it`() {
        assertNull(CardText.thirdLine(nfl(state = GameState.OFF)))
    }

    @Test
    fun `bases loaded is said once`() {
        assertEquals("Bases loaded", CardText.runners(Situation(onFirst = true, onSecond = true, onThird = true)))
        assertNull(CardText.runners(Situation()))
    }

    @Test
    fun `a ranked side carries its rank`() {
        assertEquals("7 Ohio State", CardText.teamName(7, "Ohio State"))
        assertEquals("Ohio State", CardText.teamName(null, "Ohio State"))
    }

    @Test
    fun `the line score lines up and baseball gets hits and errors`() {
        val g = nfl(state = GameState.FINAL).copy(
            leagueId = "mlb",
            away = Side("1", "Phillies", "Phillies", "PHI", 1, lineScore = listOf("1", "0"), hits = 4, errors = 0),
            home = Side("2", "Marlins", "Marlins", "MIA", 3, lineScore = listOf("0", "3"), hits = 7, errors = 1),
        )
        val rows = CardText.lineScoreRows(g, SportKind.BASEBALL)
        assertEquals(3, rows.size)
        assertEquals(rows[0].length, rows[1].length)
        assertEquals(rows[1].length, rows[2].length)
        assertTrue(rows[0].trimEnd().endsWith("R  H  E"))
        assertTrue(rows[2].startsWith("MIA"))
        assertTrue(rows[2].trimEnd().endsWith("3  7  1"))
    }

    @Test
    fun `no line score means no table`() {
        assertTrue(CardText.lineScoreRows(nfl(), SportKind.FOOTBALL).isEmpty())
    }

    @Test
    fun `a scoring play says when and who`() {
        val p = ScoringPlay("1", "TD pass", "TD", 4, "8:12", null, "26", "SEA", 7, 14)
        assertEquals("Q4 8:12 · SEA", CardText.scoringMoment(p, SportKind.FOOTBALL))
        val hr = p.copy(periodLabel = "Bot 3rd", clock = null, teamAbbrev = "MIA")
        assertEquals("Bot 3rd · MIA", CardText.scoringMoment(hr, SportKind.BASEBALL))
    }

    @Test
    fun `soccer moments read the way a match report does`() {
        assertEquals(
            "74' Goal · B. Saka",
            CardText.momentLine(Moment("74'", "Goal", "1", "B. Saka", scoring = true)),
        )
        assertEquals(
            "12' Yellow card · R. Rice",
            CardText.momentLine(Moment("12'", "Yellow Card", "1", "R. Rice", scoring = false, yellowCard = true)),
        )
    }

    @Test
    fun `ago counts in minutes then hours`() {
        val now = 10_000_000L
        assertEquals("just now", CardText.ago(now - 10_000, now))
        assertEquals("5 min ago", CardText.ago(now - 5 * 60_000, now))
        assertEquals("3 hr ago", CardText.ago(now - 3 * 3_600_000, now))
    }
}
