package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.League
import com.gios.brightsportslite.model.Provider
import com.gios.brightsportslite.model.ScoringPlay
import com.gios.brightsportslite.model.SportKind
import com.gios.brightsportslite.model.TeamRef
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one place that knows which provider serves which league. Everything above this
 * works in [Game] and [TeamRef] and never sees a URL.
 *
 * Every call here blocks on the network; callers run it on `Dispatchers.IO`.
 */
class SportsRepository(filesDir: File) {

    private val cacheDir: File = File(filesDir, "cache").apply { mkdirs() }

    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dashed = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** What one pass over the followed leagues brought back. */
    data class Fetch(val games: List<Game>, val leagues: Int, val failed: Int) {
        /** Every league failed: no signal, most likely. Keep what was on screen. */
        val offline: Boolean get() = leagues > 0 && failed == leagues
    }

    // ---------------------------------------------------------------- teams

    /**
     * Team lists change once a year at most, so they are cached for a week and served
     * stale on a network failure. Without that the picker would be empty in a subway,
     * which is exactly where someone edits their teams.
     */
    fun teams(league: League): List<TeamRef> {
        val body = when (league.provider) {
            Provider.ESPN -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                // A league with a `groups` filter (college football) needs the standings
                // tree; the plain teams endpoint ignores that filter.
                if (league.espnGroup != null) EspnParser.standingsUrl(league)
                else EspnParser.teamsUrl(league),
                TEAM_CACHE_MILLIS,
            )
            Provider.STATSAPI -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                StatsApiParser.teamsUrl(league), TEAM_CACHE_MILLIS,
            )
            Provider.HOCKEYTECH -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                HockeyTechParser.teamsUrl(league), TEAM_CACHE_MILLIS,
            )
            Provider.WPBL -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                WpblParser.teamsUrl(), TEAM_CACHE_MILLIS,
            )
        } ?: return emptyList()

        return runCatching {
            when (league.provider) {
                Provider.ESPN -> if (league.espnGroup != null) {
                    EspnParser.parseTeamsFromStandings(league.id, body)
                } else {
                    EspnParser.parseTeams(league.id, body)
                }
                Provider.STATSAPI -> StatsApiParser.parseTeams(league.id, body)
                Provider.HOCKEYTECH -> HockeyTechParser.parseTeams(league.id, body)
                Provider.WPBL -> WpblParser.parseTeams(league.id, body)
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- games

    /**
     * ESPN's scoreboard for a date window, asked three ways, the same as the full app.
     *
     * Since 2026-09-15 ESPN has answered every `dates=start-end` request with HTTP 400,
     * while a single month and no window at all still work. So: the range first (it is
     * the right question and may come back), then the calendar months the window
     * touches folded back down to the window, then ESPN's own default window. A refused
     * range is parked for six hours so each refresh costs one request, not three.
     */
    private fun espnScoreboard(path: String, group: String?, fromYmd: String, toYmd: String): String? {
        if (System.currentTimeMillis() >= rangeQueryParkedUntil) {
            val ranged = Http.get(EspnParser.pathScoreboardUrl(path, fromYmd, toYmd, group))
            if (ranged != null && ranged.contains("\"events\"")) {
                rangeQueryParkedUntil = 0L
                return ranged
            }
            rangeQueryParkedUntil = System.currentTimeMillis() + RANGE_PARK_MILLIS
        }
        val months = EspnParser.monthsIn(fromYmd, toYmd)
            .mapNotNull { Http.get(EspnParser.monthScoreboardUrl(path, it, group)) }
        EspnParser.mergeScoreboards(months, fromYmd, toYmd)?.let { return it }
        return Http.get(EspnParser.pathScoreboardUrl(path, fromYmd, toYmd, group, windowed = false))
    }

    /**
     * Games for one league across the window, or null when the provider could not be
     * reached at all. Not cached — a scoreboard is stale the moment it lands.
     */
    fun games(league: League, nowMillis: Long, zone: ZoneId): List<Game>? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from = today.minusDays(BACK_DAYS)
        val to = today.plusDays(AHEAD_DAYS)
        val body = when (league.provider) {
            Provider.ESPN -> espnScoreboard(
                league.espnPath.orEmpty(), league.espnGroup, from.format(ymd), to.format(ymd),
            )
            Provider.STATSAPI -> Http.get(
                StatsApiParser.scheduleUrl(league, from.format(dashed), to.format(dashed)),
            )
            Provider.HOCKEYTECH -> Http.get(
                HockeyTechParser.scorebarUrl(league, BACK_DAYS.toInt(), AHEAD_DAYS.toInt()),
            )
            // No date parameter on this one: it answers with the whole season and the
            // window is applied after parsing.
            Provider.WPBL -> Http.get(WpblParser.gamesUrl())
        }

        // A failed league fetch must not take the cups down with it: in August the
        // Leagues Cup is where the games actually are.
        val leagueGames = if (body == null) null else runCatching {
            when (league.provider) {
                // The cached team list is what makes an all-star fixture recognisable:
                // it carries no headline, only competitors missing from the roster.
                Provider.ESPN -> EspnParser.parseScoreboard(
                    league, body,
                    rosterIds = if (league.hasEvents) {
                        teams(league).map { it.teamId }.toSet()
                    } else {
                        emptySet()
                    },
                )
                Provider.STATSAPI -> StatsApiParser.parseSchedule(league, body)
                Provider.HOCKEYTECH -> HockeyTechParser.parseScorebar(league, body)
                Provider.WPBL -> WpblParser.parseGames(
                    league, body,
                    roster = teams(league),
                    fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                    toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
        }.getOrNull()

        val cups = cupGames(league, from.format(ymd), to.format(ymd))
        if (leagueGames == null && cups == null) return null
        return leagueGames.orEmpty() + cups.orEmpty()
    }

    /** The knockout competitions a league's clubs also play in, or null if none answered. */
    private fun cupGames(league: League, fromYmd: String, toYmd: String): List<Game>? {
        if (league.cups.isEmpty()) return emptyList()
        var answered = false
        val out = mutableListOf<Game>()
        for (cup in league.cups) {
            val body = espnScoreboard(cup.path, null, fromYmd, toYmd) ?: continue
            answered = true
            out += runCatching {
                // No roster check here: a cup field is full of clubs from other leagues,
                // so every game would look like an all-star fixture.
                EspnParser.parseScoreboard(league, body, competition = cup.name)
            }.getOrDefault(emptyList())
        }
        return if (answered) out else null
    }

    /**
     * Everything the followed teams are involved in, across every league they span, and
     * nothing else. Leagues are fetched one at a time; a follow set usually touches two
     * or three.
     */
    fun followedGames(follows: Set<String>, nowMillis: Long, zone: ZoneId): Fetch {
        if (follows.isEmpty()) return Fetch(emptyList(), 0, 0)
        val out = mutableListOf<Game>()
        var leagues = 0
        var failed = 0
        for (leagueId in Follows.leagueIds(follows)) {
            val league = Leagues.byId(leagueId) ?: continue
            leagues++
            val games = games(league, nowMillis, zone)
            if (games == null) failed++ else out += games.filter { it.involves(follows) }
        }
        return Fetch(out.distinctBy { "${it.leagueId}:${it.id}:${it.competition}" }, leagues, failed)
    }

    /** The same game, fetched again, for the game screen while it is live. */
    fun game(leagueId: String, gameId: String, nowMillis: Long, zone: ZoneId): Game? {
        val league = Leagues.byId(leagueId) ?: return null
        return games(league, nowMillis, zone)?.firstOrNull { it.id == gameId }
    }

    /**
     * The scoring plays of one game. Half a megabyte a fetch, so a finished game's list
     * is written to disk and never fetched again; a live game's is fetched each time the
     * game screen refreshes, which is at most once a minute.
     */
    fun scoring(league: League, gameId: String, final: Boolean): List<ScoringPlay>? {
        if (!hasScoringSummary(league)) return emptyList()
        val url = EspnParser.summaryUrl(league, gameId)
        val body = if (final) {
            Http.cached(cacheDir, "summary-$gameId.json", url, Long.MAX_VALUE)
        } else {
            Http.get(url)
        } ?: return null
        return runCatching { EspnParser.parseScoringPlays(body) }.getOrDefault(emptyList())
    }

    /**
     * The recap story of a finished game, one entry per paragraph, or empty when ESPN has
     * not written one. Same body as [scoring] and cached with it, so the two together
     * cost one request. Asked for only when the reader opens the recap.
     */
    fun recap(league: League, gameId: String): List<String>? {
        if (league.provider != Provider.ESPN) return emptyList()
        val url = EspnParser.summaryUrl(league, gameId)
        val body = Http.cached(cacheDir, "summary-$gameId.json", url, Long.MAX_VALUE)
            ?: return null
        return runCatching { EspnParser.parseRecapStory(body) }.getOrDefault(emptyList())
    }

    /** Drops cached game summaries older than a month, so the cache cannot grow forever. */
    fun trimCache(nowMillis: Long) {
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("summary-") && nowMillis - f.lastModified() > SUMMARY_KEEP_MILLIS) {
                f.delete()
            }
        }
    }

    companion object {
        /**
         * Whether [scoring] has anything to offer for this league. Basketball's list is
         * forty baskets long and soccer's story is already on the scoreboard as a
         * timeline, so neither asks for the half-megabyte summary.
         */
        fun hasScoringSummary(league: League): Boolean =
            league.provider == Provider.ESPN && league.kind != SportKind.BASKETBALL &&
                league.kind != SportKind.SOCCER

        private const val TEAM_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val SUMMARY_KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** How long a refused `dates=start-end` query is left alone. */
        private const val RANGE_PARK_MILLIS = 6L * 60 * 60 * 1000

        /**
         * When the range query may be tried again. Process-lifetime only on purpose, so a
         * cold start always asks ESPN once.
         */
        @Volatile
        private var rangeQueryParkedUntil = 0L

        /**
         * The window. Narrower than the full app's fortnight on purpose: three days of
         * results and a week of schedule is a column you can read to the end.
         */
        const val BACK_DAYS = 3L
        const val AHEAD_DAYS = 7L
    }
}
