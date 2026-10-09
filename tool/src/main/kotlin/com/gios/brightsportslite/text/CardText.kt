package com.gios.brightsportslite.text

import com.gios.brightsportslite.data.Leagues
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Moment
import com.gios.brightsportslite.model.ScoringPlay
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.model.Situation
import com.gios.brightsportslite.model.SportKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every string the score rows and the game screen print, worked out away from Compose so
 * it can be tested. The wording follows the full app: "Q2 3:24" live, "FINAL/OT" after,
 * "SUN 1:00 PM" before.
 */
object CardText {

    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayTime = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val dayDate = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    fun time(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(time)

    fun dayTime(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(dayTime)

    fun dayDate(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(dayDate)

    /** "just now", "4 min ago" — the one line that says whether to trust the screen. */
    fun ago(millis: Long, nowMillis: Long): String {
        if (millis <= 0) return ""
        val seconds = (nowMillis - millis) / 1000
        return when {
            seconds < 45 -> "just now"
            seconds < 90 -> "1 min ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 7200 -> "1 hr ago"
            else -> "${seconds / 3600} hr ago"
        }
    }

    fun periodLabel(kind: SportKind, period: Int): String {
        if (period <= 0) return ""
        return when (kind) {
            SportKind.BASKETBALL -> if (period > 4) ot(period - 4) else "Q$period"
            SportKind.FOOTBALL -> if (period > 4) ot(period - 4) else "Q$period"
            SportKind.HOCKEY -> if (period > 3) ot(period - 3) else "P$period"
            SportKind.SOCCER -> if (period > 2) ot(period - 2) else "H$period"
            SportKind.BASEBALL -> ordinal(period)
        }
    }

    private fun ot(n: Int) = if (n <= 1) "OT" else "${n}OT"

    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * Whether the game is paused between periods: halftime, an intermission, the end of a
     * quarter. ESPN names some of these in the status enum and only spells others out in
     * the detail text, so both are read.
     */
    fun explicitBoundary(statusName: String?, statusDetail: String): Boolean {
        val name = statusName.orEmpty().uppercase()
        if ("HALFTIME" in name || "END_PERIOD" in name || "END_OF_PERIOD" in name ||
            "INTERMISSION" in name
        ) return true
        val text = statusDetail.lowercase().trim()
        return text.startsWith("end of") || text.startsWith("end ") ||
            text == "ht" || text == "half" || text == "halftime" || text.startsWith("int")
    }

    /** "Q2 3:24" while the clock runs, else the provider's own words: "HALFTIME", "BOT 7TH". */
    fun liveLabel(game: Game, kind: SportKind?): String {
        if (kind == SportKind.FOOTBALL || kind == SportKind.BASKETBALL || kind == SportKind.HOCKEY) {
            val period = periodLabel(kind, game.period)
            if (period.isNotEmpty() && game.clock != null &&
                !explicitBoundary(game.statusName, game.statusDetail)
            ) return "$period ${game.clock}"
        }
        return game.statusDetail.ifEmpty { "Live" }.uppercase()
    }

    /** The top line of a score row: what this is, and where it stands. */
    fun statusLine(game: Game, zone: ZoneId): String {
        val league = Leagues.byId(game.leagueId)
        val what = game.eventTitle?.uppercase() ?: game.competition?.uppercase() ?: league?.short
        val where = when (game.state) {
            GameState.PRE -> dayTime(game.startMillis, zone).uppercase()
            GameState.LIVE -> liveLabel(game, league?.kind)
            GameState.FINAL -> game.statusDetail.ifEmpty { "Final" }.uppercase()
            GameState.OFF -> game.statusDetail.ifEmpty { "Postponed" }.uppercase()
        }
        return listOfNotNull(what, where).joinToString(" · ")
    }

    /**
     * The line under the two teams: what is happening (live), what to expect (pre-game),
     * or what happened (final). Null when there is nothing to say.
     */
    fun thirdLine(game: Game): String? {
        val kind = Leagues.byId(game.leagueId)?.kind
        return when (game.state) {
            GameState.LIVE -> situationLine(game, kind)
            GameState.FINAL -> game.headline
            GameState.PRE -> listOfNotNull(game.odds, game.overUnder?.let { "O/U $it" }, game.weather)
                .joinToString(" · ").takeIf { it.isNotEmpty() }
            GameState.OFF -> null
        }
    }

    /** Football: down and distance. Baseball: the count, the outs, the runners. */
    fun situationLine(game: Game, kind: SportKind?): String? {
        val s = game.situation ?: return null
        return when (kind) {
            SportKind.FOOTBALL -> listOfNotNull(s.downDistance, "Red zone".takeIf { s.isRedZone })
                .joinToString(" · ").takeIf { it.isNotEmpty() }
            SportKind.BASEBALL -> listOfNotNull(
                if (s.balls != null && s.strikes != null) "${s.balls}-${s.strikes}" else null,
                s.outs?.let { if (it == 1) "1 out" else "$it outs" },
                runners(s),
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    fun runners(s: Situation): String? {
        val on = listOfNotNull(
            "1st".takeIf { s.onFirst }, "2nd".takeIf { s.onSecond }, "3rd".takeIf { s.onThird },
        )
        return when (on.size) {
            0 -> null
            3 -> "Bases loaded"
            1 -> "Runner on ${on[0]}"
            else -> "Runners on ${on[0]} and ${on[1]}"
        }
    }

    /** One side's name as the row prints it: the poll rank first, when there is one. */
    fun teamName(rank: Int?, name: String): String = if (rank != null) "$rank $name" else name

    /** The heading over a line score table. */
    fun lineScoreTitle(kind: SportKind): String = when (kind) {
        SportKind.BASEBALL -> "By inning"
        SportKind.HOCKEY -> "By period"
        SportKind.SOCCER -> "By half"
        else -> "By quarter"
    }

    fun periodHeader(kind: SportKind, period: Int): String = when (kind) {
        SportKind.BASEBALL -> period.toString()
        else -> periodLabel(kind, period)
    }

    fun totalLabel(kind: SportKind): String = if (kind == SportKind.BASEBALL) "R" else "T"

    /** "Q4 8:12 · SEA" under a scoring play. */
    fun scoringMoment(play: ScoringPlay, kind: SportKind): String {
        val period = play.periodLabel ?: periodLabel(kind, play.period).takeIf { it.isNotEmpty() }
        val moment = listOfNotNull(period, play.clock).joinToString(" ")
        return listOfNotNull(moment.takeIf { it.isNotEmpty() }, play.teamAbbrev).joinToString(" · ")
    }

    /** "74' Goal · B. Saka" for a soccer timeline entry. */
    fun momentLine(m: Moment): String {
        val what = when {
            m.ownGoal -> "Own goal"
            m.redCard -> "Red card"
            m.yellowCard -> "Yellow card"
            m.penalty && m.scoring -> "Penalty goal"
            m.scoring -> "Goal"
            else -> m.type
        }
        return listOfNotNull(
            listOfNotNull(m.clock, what).joinToString(" "),
            m.player,
        ).joinToString(" · ")
    }

    /**
     * The line score as three rows of fixed-width text — header, away, home — for a
     * monospace block that scrolls sideways. An eleven-inning game does not fit across a
     * 3.9 inch screen and a tiny font is no use to anybody. Empty when there is no line.
     */
    fun lineScoreRows(game: Game, kind: SportKind): List<String> {
        val periods = maxOf(game.away.lineScore.size, game.home.lineScore.size)
        if (periods == 0) return emptyList()
        val nameWidth = maxOf(game.away.abbrev.length, game.home.abbrev.length, 2)
        val headers = (1..periods).map { periodHeader(kind, it) }
        val baseball = kind == SportKind.BASEBALL && (game.away.hits != null || game.home.hits != null)
        val cell = maxOf(3, headers.maxOf { it.length } + 1)
        fun row(name: String, cells: List<String>, total: String, extra: List<String>): String = buildString {
            append(name.padEnd(nameWidth))
            for (c in cells) append(c.padStart(cell))
            append(" ")
            append(total.padStart(3))
            for (e in extra) append(e.padStart(3))
        }
        fun lineOf(side: Side) =
            (0 until periods).map { side.lineScore.getOrNull(it) ?: "" }
        return listOf(
            row("", headers, totalLabel(kind), if (baseball) listOf("H", "E") else emptyList()),
            row(
                game.away.abbrev, lineOf(game.away), game.away.score?.toString() ?: "",
                if (baseball) listOf(game.away.hits?.toString() ?: "", game.away.errors?.toString() ?: "") else emptyList(),
            ),
            row(
                game.home.abbrev, lineOf(game.home), game.home.score?.toString() ?: "",
                if (baseball) listOf(game.home.hits?.toString() ?: "", game.home.errors?.toString() ?: "") else emptyList(),
            ),
        )
    }

    /** The kickoff row's label on the game screen. */
    fun startLabel(kind: SportKind): String = when (kind) {
        SportKind.BASEBALL -> "First pitch"
        SportKind.FOOTBALL -> "Kickoff"
        SportKind.BASKETBALL -> "Tip-off"
        else -> "Start"
    }
}
