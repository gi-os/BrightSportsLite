package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.League
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.model.TeamRef
import com.gios.brightsportslite.json.JSONArray
import com.gios.brightsportslite.json.JSONObject

/**
 * MLB's StatsAPI, used here only for the minor leagues — ESPN publishes no MiLB
 * scoreboard, and this is the same feed MiLB.com itself runs on. Keyless.
 *
 * `sportId` selects the level: 11 Triple-A, 12 Double-A, 13 High-A, 14 Single-A.
 */
object StatsApiParser {

    private const val BASE = "https://statsapi.mlb.com/api/v1"

    fun scheduleUrl(league: League, startDate: String, endDate: String): String =
        "$BASE/schedule?sportId=${league.statsApiSportId}" +
            "&startDate=$startDate&endDate=$endDate" +
            "&hydrate=team,linescore,venue,broadcasts(all)"

    fun teamsUrl(league: League): String =
        "$BASE/teams?sportId=${league.statsApiSportId}&activeStatus=Y"

    // ---------------------------------------------------------------- teams

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val teams = JSONObject(body).optJSONArray("teams") ?: return emptyList()
        return teams.objects().map { t ->
            val parent = t.optString("parentOrgName").takeIf { it.isNotEmpty() }
            TeamRef(
                leagueId = leagueId,
                teamId = t.optInt("id").toString(),
                // The affiliate is the reason to follow a farm club at all, so it
                // rides along in the display name: "Reno Aces (ARI)".
                displayName = t.optString("name") +
                    (parent?.let { " (${orgAbbrev(it)})" } ?: ""),
                short = t.optString("teamName").ifEmpty { t.optString("name") },
                abbrev = t.optString("abbreviation").ifEmpty {
                    t.optString("teamCode").uppercase()
                },
            )
        }.sortedBy { it.displayName }
    }

    /** "San Francisco Giants" -> "SFG"-ish. Only used as a parenthetical hint. */
    private fun orgAbbrev(org: String): String {
        val words = org.split(' ').filter { it.isNotEmpty() }
        return if (words.size >= 2) {
            words.dropLast(1).joinToString("") { it.take(1).uppercase() } +
                words.last().take(1).uppercase()
        } else org.take(3).uppercase()
    }

    // ---------------------------------------------------------------- games

    fun parseSchedule(league: League, body: String): List<Game> {
        val dates = JSONObject(body).optJSONArray("dates") ?: return emptyList()
        val out = mutableListOf<Game>()
        for (d in dates.objects()) {
            for (g in (d.optJSONArray("games") ?: JSONArray()).objects()) {
                val teams = g.optJSONObject("teams") ?: continue
                val line = g.optJSONObject("linescore")
                val home = side(teams.optJSONObject("home"), line, home = true) ?: continue
                val away = side(teams.optJSONObject("away"), line, home = false) ?: continue
                val status = g.optJSONObject("status")
                out += Game(
                    id = g.optInt("gamePk").toString(),
                    leagueId = league.id,
                    state = state(status),
                    startMillis = Iso.millis(g.optString("gameDate")),
                    statusDetail = statusDetail(status, line),
                    period = line?.optInt("currentInning") ?: 0,
                    clock = null,
                    home = home,
                    away = away,
                    venue = g.optJSONObject("venue")?.optString("name")?.takeIf { it.isNotEmpty() },
                    broadcast = broadcast(g),
                    note = g.optString("seriesDescription").takeIf {
                        it.isNotEmpty() && it != "Regular Season"
                    },
                )
            }
        }
        return dedupe(out)
    }

    /**
     * A postponed game with a make-up date lands in this response twice — once under
     * its original date reporting `detailedState: "Postponed"`, and again under the
     * reschedule date reporting the generic placeholder `"Scheduled"` — same `gamePk`,
     * two conflicting states, confirmed live (Brooklyn Cyclones, gamePk 821809,
     * 2026-08-07/08). Both entries survive the date-window filter in
     * `SportsRepository.games` since `BACK_DAYS`/`AHEAD_DAYS` spans several days either
     * side. Downstream, `ScoreWatcher.poll` writes one snapshot per game *id* and the
     * later entry in the list silently overwrites the earlier one's — so whichever
     * duplicate is processed last decides what gets remembered, and if that is the
     * bland "Scheduled" placeholder, the postponement never actually gets marked seen.
     * The next poll finds the same stale "not yet seen" snapshot, re-detects the
     * transition into OFF, and repeats forever.
     *
     * Deduping by id and keeping the more specific state (OFF or FINAL over a plain
     * PRE) fixes it at the source rather than papering over it downstream: there is
     * only ever one [Game] per id from here on, so there is nothing left to overwrite.
     */
    private fun dedupe(games: List<Game>): List<Game> {
        fun rank(g: Game) = when (g.state) {
            GameState.OFF -> 3
            GameState.FINAL -> 2
            GameState.LIVE -> 1
            GameState.PRE -> 0
        }
        return games.groupBy { it.id }.values.map { dupes ->
            dupes.maxByOrNull { rank(it) } ?: dupes.first()
        }
    }

    private fun side(node: JSONObject?, line: JSONObject?, home: Boolean): Side? {
        val t = node?.optJSONObject("team") ?: return null
        val key = if (home) "home" else "away"
        val innings = (line?.optJSONArray("innings") ?: JSONArray()).objects()
        val rec = node.optJSONObject("leagueRecord")
        return Side(
            teamId = t.optInt("id").toString(),
            displayName = t.optString("name"),
            short = t.optString("teamName").ifEmpty { t.optString("name") },
            abbrev = t.optString("abbreviation").ifEmpty { t.optString("teamCode").uppercase() },
            score = if (node.has("score")) node.optInt("score") else null,
            record = rec?.let { "${it.optInt("wins")}-${it.optInt("losses")}" },
            lineScore = innings.map { inning ->
                val half = inning.optJSONObject(key)
                // A half-inning that hasn't been played yet has no "runs" key at all,
                // which is how the bottom of the ninth stays blank in a walk-off.
                if (half != null && half.has("runs")) half.optInt("runs").toString() else "-"
            },
            hits = line?.optJSONObject("teams")?.optJSONObject(key)?.optInt("hits", -1)
                ?.takeIf { it >= 0 },
            errors = line?.optJSONObject("teams")?.optJSONObject(key)?.optInt("errors", -1)
                ?.takeIf { it >= 0 },
        )
    }

    /**
     * `detailedState` has to be checked before `abstractGameState`, not nested inside
     * a `"Preview"` branch — confirmed live, StatsAPI does not restrict these words to
     * games that haven't started. A postponed MiLB game reports `abstractGameState:
     * "Final"` (which without this check reads as a completed game, not a rainout,
     * and fires a FINAL alert that repeats every time the reschedule flips the feed
     * back through "Preview" and forward again); a suspended one reports `"Live"`, so
     * the delay itself would otherwise carry no state change — and therefore no
     * one-time alert and no resume alert either — at all.
     */
    private fun state(status: JSONObject?): GameState {
        val detailed = status?.optString("detailedState").orEmpty()
        if (detailed.contains("Postponed") || detailed.contains("Cancelled") ||
            detailed.contains("Suspended") || detailed.contains("Delayed")
        ) return GameState.OFF
        return when (status?.optString("abstractGameState")) {
            "Live" -> GameState.LIVE
            "Final" -> GameState.FINAL
            "Preview" -> GameState.PRE
            else -> GameState.PRE
        }
    }

    private fun statusDetail(status: JSONObject?, line: JSONObject?): String {
        val detailed = status?.optString("detailedState").orEmpty()
        if (status?.optString("abstractGameState") != "Live") return detailed
        val half = line?.optString("inningState")?.take(3).orEmpty()
        val ord = line?.optString("currentInningOrdinal").orEmpty()
        return if (ord.isEmpty()) detailed else "$half $ord".trim()
    }

    private fun broadcast(g: JSONObject): String? =
        (g.optJSONArray("broadcasts") ?: JSONArray()).objects()
            .firstOrNull { it.optString("type") == "TV" }
            ?.optString("name")?.takeIf { it.isNotEmpty() }
}
