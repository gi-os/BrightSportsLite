package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.EventClass
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.League
import com.gios.brightsportslite.model.Moment
import com.gios.brightsportslite.model.ScoringPlay
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.model.Situation
import com.gios.brightsportslite.model.TeamRef
import com.gios.brightsportslite.json.JSONArray
import com.gios.brightsportslite.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * ESPN's site API. Undocumented but stable for a decade, keyless, and identical in
 * shape across every team sport, which is the only reason one parser covers eight
 * leagues.
 */
object EspnParser {

    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val CORE = "https://site.api.espn.com/apis/v2/sports"

    /**
     * `limit` has to clear the busiest window in the calendar and must not go over 500.
     *
     * Both ends of that bite. A month of MLB is around 400 games league-wide, and a limit
     * under it cuts the tail off silently — no error, just a short list, which is
     * indistinguishable from a quiet month until you count. That is why this was raised
     * to 1000 in the first place.
     *
     * But **college football answers any limit above 500 with its default 25 events**.
     * Measured 2026-09-16 against the Saturday of 19 September, which has 71 FBS games on
     * it: limits from 36 to 500 all return 71, 501 and everything above it return 25, and
     * dropping the parameter entirely also returns 71. FCS behaves the same way. No other
     * league does this — the NFL, MLB, the NHL and the soccer leagues all answer 1000
     * normally — so for a year the app asked for every college football game and was
     * handed a third of them, and a followed team outside those 25 simply had no game
     * that week. It reads as a team going missing, never as a truncation.
     *
     * 500 clears every league's busiest month and sits under the cliff. Raising it breaks
     * college football silently, so anything that changes this number wants a live count
     * against a full Saturday, not a compile.
     */
    private const val LIMIT = 500

    /**
     * See [LIMIT].
     */
    fun scoreboardUrl(
        league: League,
        startYmd: String,
        endYmd: String,
        windowed: Boolean = true,
    ): String = pathScoreboardUrl(league.espnPath.orEmpty(), startYmd, endYmd, league.espnGroup, windowed)

    /**
     * Any ESPN competition path, which is how the cups are reached. `groups` is plural
     * here, confirmed against college football: `groups=80` is what actually narrows the
     * scoreboard to FBS games.
     *
     * `windowed = false` drops the `dates=` range. ESPN 400s every ranged scoreboard
     * query since 2026-09-15 ("Failed to get events endpoint"), and the endpoint without
     * it still answers with ESPN's default window. See `SportsRepository.espnScoreboard`.
     */
    fun pathScoreboardUrl(
        path: String,
        startYmd: String,
        endYmd: String,
        group: String? = null,
        windowed: Boolean = true,
    ): String = buildString {
        append("$SITE/$path/scoreboard?limit=$LIMIT")
        if (windowed) append("&dates=$startYmd-$endYmd")
        group?.let { append("&groups=$it") }
    }

    /**
     * The same scoreboard asked for a whole calendar month, `dates=YYYYMM`.
     *
     * ESPN rejects `dates=start-end` but still answers a month, a single day and a year,
     * so a month is the widest window left that keeps both directions of the feed: the
     * results behind today and the fixtures in front of it. Two of these cover any window
     * this app asks for. See [monthsIn] and [mergeScoreboards].
     */
    fun monthScoreboardUrl(path: String, yyyymm: String, group: String? = null): String =
        buildString {
            append("$SITE/$path/scoreboard?limit=$LIMIT&dates=$yyyymm")
            group?.let { append("&groups=$it") }
        }

    /**
     * Every calendar month a window touches, as `YYYYMM`, oldest first.
     *
     * Capped at three. The widest window the feed asks for is a fortnight either side of
     * a paged week, which cannot straddle more than three months, and an uncapped loop on
     * an end date that parses but precedes the start would never finish.
     */
    fun monthsIn(startYmd: String, endYmd: String): List<String> {
        val start = runCatching { LocalDate.parse(startYmd, YMD) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(endYmd, YMD) }.getOrNull() ?: return emptyList()
        var month = YearMonth.from(start)
        val last = YearMonth.from(maxOf(start, end))
        val out = mutableListOf<String>()
        while (!month.isAfter(last) && out.size < 3) {
            out += month.format(YM)
            month = month.plusMonths(1)
        }
        return out
    }

    /**
     * Fold month scoreboards back into one body holding only the window that was asked
     * for, in the shape [parseScoreboard] reads.
     *
     * The filter is what makes the month query safe to send to every league: a month is a
     * far wider net than the window, and without it a page would draw three-week-old games
     * as this week's slate. A day of slack either side covers a night game whose UTC stamp
     * has already rolled over; the feed buckets to the exact window afterwards.
     *
     * v2.11 said here that college football answers a month with one week of it. It does
     * not. That was [LIMIT] at 1000, which collapses the college football scoreboard to 25
     * events whatever is asked for — at 500 the month comes back whole, 323 games across
     * September. The measurement was right and the cause was wrong.
     *
     * Returns null when nothing falls inside, which is the signal to fall through.
     */
    fun mergeScoreboards(bodies: List<String>, startYmd: String, endYmd: String): String? {
        val start = runCatching { LocalDate.parse(startYmd, YMD) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(endYmd, YMD) }.getOrNull() ?: return null
        val low = start.minusDays(1).format(YMD)
        val high = end.plusDays(1).format(YMD)
        val seen = mutableSetOf<String>()
        val kept = JSONArray()
        for (body in bodies) {
            val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: continue
            for (event in events.objects()) {
                val day = event.optString("date").take(10).replace("-", "")
                if (day.length != 8 || day < low || day > high) continue
                val id = event.optString("id")
                if (id.isNotEmpty() && !seen.add(id)) continue
                kept.put(event)
            }
        }
        if (kept.length() == 0) return null
        return JSONObject().put("events", kept).toString()
    }

    private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val YM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

    fun teamsUrl(league: League): String = "$SITE/${league.espnPath}/teams?limit=400"

    /**
     * level=3 asks for divisions rather than conferences. Leagues without divisions
     * ignore it, so it is safe to send everywhere.
     */
    fun standingsUrl(league: League): String {
        val base = "$CORE/${league.espnPath}/standings?level=3"
        // Singular here, unlike the scoreboard's `groups=` — verified live against
        // college football's FBS/FCS split, which is the only league that needs it.
        return league.espnGroup?.let { "$base&group=$it" } ?: base
    }

    /**
     * The game summary: box score, drives, scoring plays, win probability, news. Around
     * 580 KB for a finished game, which is why it is fetched once per game on request
     * rather than polled.
     */
    fun summaryUrl(league: League, eventId: String): String =
        "$SITE/${league.espnPath}/summary?event=$eventId"


    /**
     * The summary's `scoringPlays`, in game order.
     *
     * Baseball does not have that array. Its summary carries the whole play-by-play
     * under `plays` instead, with the runs flagged `scoringPlay`, so a game that looked
     * like it had no scoring summary in fact had every run in it — six hundred plays
     * away from the key the parser was reading. When `scoringPlays` is absent, filter
     * `plays` for the scoring ones and read them the same way.
     */
    fun parseScoringPlays(body: String): List<ScoringPlay> {
        val root = JSONObject(body)
        val listed = root.optJSONArray("scoringPlays")
        val plays = listed ?: root.optJSONArray("plays") ?: return emptyList()
        return plays.objects().mapNotNull { p ->
            if (listed == null && !p.optBoolean("scoringPlay", false)) return@mapNotNull null
            val text = p.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val team = p.optJSONObject("team")
            val period = p.optJSONObject("period")
            ScoringPlay(
                id = p.optString("id"),
                text = text,
                kind = p.optJSONObject("scoringType")?.optString("abbreviation")
                    ?.takeIf { it.isNotEmpty() } ?: p.optJSONObject("type")
                    ?.optString("abbreviation")?.takeIf { it.isNotEmpty() }
                    // Baseball names the play under `alternativeType`: HR, 1B, 2B, SF.
                    // `type` there is the flat "Play Result", which says nothing.
                    ?: p.optJSONObject("alternativeType")?.optString("abbreviation").orEmpty(),
                period = period?.optInt("number") ?: 0,
                clock = p.optJSONObject("clock")?.optString("displayValue")?.takeIf { it.isNotEmpty() },
                periodLabel = halfLabel(period),
                teamId = team?.optString("id")?.takeIf { it.isNotEmpty() },
                teamAbbrev = team?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
                awayScore = p.optInt("awayScore", 0),
                homeScore = p.optInt("homeScore", 0),
            )
        }
    }

    /** "TOP 3RD", "BOT 8TH" — baseball's half-inning, or null for the sports without one. */
    private fun halfLabel(period: JSONObject?): String? {
        val half = when (period?.optString("type").orEmpty().lowercase()) {
            "top" -> "Top"
            "bottom" -> "Bot"
            else -> return null
        }
        val number = period?.optInt("number") ?: 0
        if (number <= 0) return null
        return "$half ${ordinal(number)}"
    }

    private fun ordinal(n: Int): String {
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
     * The recap story from a game summary, one entry per paragraph. ESPN files it as
     * HTML with the players and clubs linked, and opens it with a dateline the app has
     * no use for ("DETROIT -- — "), so the tags come out, the entities are decoded and
     * the dateline is trimmed.
     *
     * Two things in the markup are structure rather than decoration and have to survive
     * the tag strip: `<hl2>` heads a section ("Up next") and belongs on its own line, and
     * a rule of dashes marks the end of the story — under it is the wire service's own
     * boilerplate, which is not part of the game.
     */
    fun parseRecapStory(body: String): List<String> {
        val article = JSONObject(body).optJSONObject("article") ?: return emptyList()
        val story = article.optString("story").takeIf { it.isNotEmpty() } ?: return emptyList()
        val headed = story.replace(
            Regex("<hl\\d?>(.*?)</hl\\d?>", RegexOption.DOT_MATCHES_ALL),
            "\n\n\$1\n\n",
        )
        val text = decodeEntities(headed.replace(Regex("<[^>]+>"), ""))
        return text.split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .mapIndexed { i, para -> if (i == 0) cleanRecap(para) else para }
            .filter { it.isNotEmpty() }
            .takeWhile { !it.matches(Regex("[-–—_]{3,}")) }
    }

    /**
     * The recap sentence as it should read on the phone. ESPN leaves the dateline's
     * trailing em dash on the front of the description field, and the story puts the
     * city in front of that again.
     */
    private fun cleanRecap(raw: String): String =
        decodeEntities(raw).replace(Regex("^\\s*[A-Z][A-Za-z.,'\\- ]{0,30}\\s+--\\s*"), "")
            .replace(Regex("^[\\s—–-]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * The handful of entities a recap actually contains, plus the numeric ones: ESPN
     * writes its em dashes and curly apostrophes as `&#8212;` and `&#8217;` about as
     * often as it writes the characters themselves.
     */
    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#(\\d{1,7});")) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }
                ?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // Last, so an `&amp;lt;` in the copy does not turn into a tag on the way through.
        .replace("&amp;", "&")

    // ---------------------------------------------------------------- teams

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val leagues = JSONObject(body).optJSONArray("sports")
            ?.optJSONObject(0)?.optJSONArray("leagues") ?: return emptyList()
        val out = mutableListOf<TeamRef>()
        for (l in leagues.objects()) {
            for (entry in (l.optJSONArray("teams") ?: JSONArray()).objects()) {
                val t = entry.optJSONObject("team") ?: continue
                // ESPN keeps relocated and defunct clubs in the list; they'd only pad
                // the picker with teams that will never appear on a scoreboard.
                if (t.has("isActive") && !t.optBoolean("isActive", true)) continue
                out += TeamRef(
                    leagueId = leagueId,
                    teamId = t.optString("id"),
                    displayName = t.optString("displayName"),
                    short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
                    abbrev = t.optString("abbreviation").ifEmpty {
                        t.optString("shortDisplayName").take(3).uppercase()
                    },
                )
            }
        }
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }

    /**
     * The roster source for any league with [League.espnGroup] set, since the plain
     * `teams` endpoint silently ignores that filter (see the field's doc comment).
     * The standings tree does honor it, and every leaf entry carries a full team
     * object — id, name, crest — so it doubles as a roster without a second request.
     */
    fun parseTeamsFromStandings(leagueId: String, body: String): List<TeamRef> {
        val out = mutableListOf<TeamRef>()
        fun walk(node: JSONObject) {
            for (entry in (node.optJSONObject("standings")
                ?.optJSONArray("entries") ?: JSONArray()).objects()) {
                val t = entry.optJSONObject("team") ?: continue
                if (t.has("isActive") && !t.optBoolean("isActive", true)) continue
                out += TeamRef(
                    leagueId = leagueId,
                    teamId = t.optString("id"),
                    displayName = t.optString("displayName"),
                    short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
                    abbrev = t.optString("abbreviation").ifEmpty {
                        t.optString("shortDisplayName").take(3).uppercase()
                    },
                )
            }
            for (child in (node.optJSONArray("children") ?: JSONArray()).objects()) {
                walk(child)
            }
        }
        walk(JSONObject(body))
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }


    // ---------------------------------------------------------------- games

    /**
     * @param rosterIds the league's own team ids, from the cached team list. An event
     *   with a competitor outside this set is an all-star or exhibition fixture — for
     *   several of them that is the only signal, since they carry no headline. Pass an
     *   empty set to skip that check; classification then falls back to the headline.
     */
    fun parseScoreboard(
        league: League,
        body: String,
        rosterIds: Set<String> = emptySet(),
        competition: String? = null,
    ): List<Game> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<Game>()
        for (e in events.objects()) {
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            var home: Side? = null
            var away: Side? = null
            for (c in competitors.objects()) {
                val side = side(c) ?: continue
                if (c.optString("homeAway") == "home") home = side else away = side
            }
            if (home == null || away == null) continue

            val headline = comp.optJSONArray("notes")?.optJSONObject(0)
                ?.optString("headline")?.takeIf { it.isNotEmpty() }
            val seasonSlug = e.optJSONObject("season")?.optString("slug")
            val offRoster = rosterIds.isNotEmpty() &&
                listOf(home, away).any { it.teamId !in rosterIds }
            val eventClass = SpecialEvents.classify(headline, seasonSlug, offRoster)

            val status = comp.optJSONObject("status") ?: e.optJSONObject("status")
            val type = status?.optJSONObject("type")
            out += Game(
                id = e.optString("id"),
                leagueId = league.id,
                state = state(type),
                startMillis = Iso.millis(e.optString("date")),
                statusDetail = type?.optString("shortDetail")
                    ?.ifEmpty { type.optString("detail") }.orEmpty(),
                period = status?.optInt("period") ?: 0,
                statusName = type?.optString("name")?.takeIf { it.isNotEmpty() },
                clock = status?.optString("displayClock")?.takeIf { it.isNotEmpty() && it != "0:00" },
                home = home,
                away = away,
                venue = comp.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotEmpty() },
                broadcast = broadcast(comp),
                note = headline,
                // Soccer names its finals only in the slug, so fall back to that.
                eventTitle = headline ?: slugTitle(seasonSlug).takeIf {
                    eventClass != EventClass.NONE
                },
                eventClass = eventClass,
                competition = competition,
                situation = comp.optJSONObject("situation")?.let { situation(it) },
                odds = comp.optJSONArray("odds")?.optJSONObject(0)?.optString("details")
                    ?.takeIf { it.isNotEmpty() },
                overUnder = comp.optJSONArray("odds")?.optJSONObject(0)?.let {
                    val ou = it.optDouble("overUnder", Double.NaN)
                    if (ou.isNaN()) null else fmtNum(ou)
                },
                weather = weather(e.optJSONObject("weather")),
                week = e.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 },
                headline = comp.optJSONArray("headlines")?.optJSONObject(0)
                    ?.optString("shortLinkText")?.takeIf { it.isNotEmpty() },
                recap = comp.optJSONArray("headlines")?.optJSONObject(0)
                    ?.optString("description")?.let { cleanRecap(it) }?.takeIf { it.isNotEmpty() },
                neutralSite = comp.optBoolean("neutralSite", false),
                timeline = timeline(comp, home.teamId, away.teamId),
            )
        }
        return out
    }

    /**
     * ESPN's live-game block. Every key is optional in practice — football fills the
     * down-and-distance half, baseball the count — so nothing here is required, and a
     * block with none of the fields still comes back as an (empty) situation rather
     * than a null one, because its presence is itself the signal that play is under way.
     */
    internal fun situation(s: JSONObject): Situation {
        val last = s.optJSONObject("lastPlay")
        return Situation(
            possession = s.optString("possession").takeIf { it.isNotEmpty() },
            downDistance = s.optString("downDistanceText").takeIf { it.isNotEmpty() },
            shortDownDistance = s.optString("shortDownDistanceText").takeIf { it.isNotEmpty() },
            spot = s.optString("possessionText").takeIf { it.isNotEmpty() },
            down = s.optInt("down", -1).takeIf { it > 0 },
            distance = s.optInt("distance", -1).takeIf { it >= 0 },
            isRedZone = s.optBoolean("isRedZone", false),
            homeTimeouts = s.optInt("homeTimeouts", -1).takeIf { it >= 0 },
            awayTimeouts = s.optInt("awayTimeouts", -1).takeIf { it >= 0 },
            lastPlay = last?.optString("text")?.takeIf { it.isNotEmpty() },
            drive = last?.optJSONObject("drive")?.optString("description")
                ?.takeIf { it.isNotEmpty() },
            balls = s.optInt("balls", -1).takeIf { it >= 0 },
            strikes = s.optInt("strikes", -1).takeIf { it >= 0 },
            outs = s.optInt("outs", -1).takeIf { it >= 0 },
            onFirst = s.optBoolean("onFirst", false),
            onSecond = s.optBoolean("onSecond", false),
            onThird = s.optBoolean("onThird", false),
            batter = s.optJSONObject("batter")?.optJSONObject("athlete")
                ?.optString("shortName")?.takeIf { it.isNotEmpty() },
            pitcher = s.optJSONObject("pitcher")?.optJSONObject("athlete")
                ?.optString("shortName")?.takeIf { it.isNotEmpty() },
            batterSummary = s.optJSONObject("batter")?.optString("summary")?.takeIf { it.isNotEmpty() },
            pitcherSummary = s.optJSONObject("pitcher")?.optString("summary")?.takeIf { it.isNotEmpty() },
        )
    }

    /** "75° Mostly sunny". Indoor games carry no block and get null. */
    private fun weather(w: JSONObject?): String? {
        if (w == null) return null
        val temp = w.optInt("temperature", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val text = w.optString("displayValue").takeIf { it.isNotEmpty() }
        return listOfNotNull(temp?.let { "$it°" }, text).joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /**
     * `mls-cup` -> `MLS Cup`, `playoffs---championship` -> `Championship`.
     *
     * Generic slugs give nothing back: an all-star game sits in `regular-season`, and
     * titling it "Regular Season" is worse than leaving it to the league name.
     */
    private fun slugTitle(slug: String?): String? {
        if (slug.isNullOrEmpty()) return null
        if (slug.lowercase().replace("-", "") in
            setOf("regularseason", "postseason", "preseason", "offseason")
        ) return null
        val tail = slug.substringAfterLast("---").replace('-', ' ').trim()
        if (tail.isEmpty()) return null
        return tail.split(' ').joinToString(" ") { word ->
            // Acronyms stay upper: mls, nwsl.
            if (word.length <= 4 && word !in setOf("cup", "final", "semi")) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun side(c: JSONObject): Side? {
        val t = c.optJSONObject("team") ?: return null
        return Side(
            teamId = t.optString("id"),
            displayName = t.optString("displayName"),
            short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
            abbrev = t.optString("abbreviation").ifEmpty { t.optString("shortDisplayName").take(3) },
            // A string on the scoreboard, an object on a team's schedule.
            score = c.optJSONObject("score")?.let { fmtNum(it.optDouble("value", Double.NaN)).toIntOrNull() }
                ?: c.optString("score").toIntOrNull(),
            record = c.optJSONArray("records")?.objects()
                ?.firstOrNull { it.optString("type") == "total" }
                ?.optString("summary")?.takeIf { it.isNotEmpty() }
                // The schedule spells it `record[]` with a `displayValue`.
                ?: c.optJSONArray("record")?.objects()
                    ?.firstOrNull { it.optString("type") == "total" }
                    ?.optString("displayValue")?.takeIf { it.isNotEmpty() },
            lineScore = (c.optJSONArray("linescores") ?: JSONArray()).objects().map {
                val display = it.optString("displayValue")
                if (display.isNotEmpty()) display else fmtNum(it.optDouble("value", 0.0))
            },
            hits = c.optInt("hits", -1).takeIf { it >= 0 },
            errors = c.optInt("errors", -1).takeIf { it >= 0 },
            // 99 is ESPN for "not in the poll".
            rank = c.optJSONObject("curatedRank")?.optInt("current", 0)
                ?.takeIf { it in 1..25 },
        )
    }

    /**
     * A soccer match's goals and cards, in order, with the running score written onto
     * each goal. Own goals count for the other side. Shootout kicks are dropped: the
     * score line already says how it ended, and a dozen rows of "penalty scored" is not
     * a story.
     */
    internal fun timeline(comp: JSONObject, homeId: String, awayId: String): List<Moment> {
        val details = comp.optJSONArray("details")?.objects() ?: return emptyList()
        var home = 0
        var away = 0
        val out = mutableListOf<Moment>()
        for (d in details) {
            if (d.optBoolean("shootout", false)) continue
            val type = d.optJSONObject("type")?.optString("text").orEmpty()
            val teamId = d.optJSONObject("team")?.optString("id")?.takeIf { it.isNotEmpty() }
            val scoring = d.optBoolean("scoringPlay", false)
            val ownGoal = d.optBoolean("ownGoal", false)
            val value = d.optInt("scoreValue", if (scoring) 1 else 0)
            if (scoring && value > 0) {
                // An own goal is credited to the team whose player scored it, and counts
                // against them.
                val credit = if (ownGoal) (if (teamId == homeId) awayId else homeId) else teamId
                if (credit == homeId) home += value else if (credit == awayId) away += value
            }
            val yellow = d.optBoolean("yellowCard", false)
            val red = d.optBoolean("redCard", false)
            // Substitutions and the like carry neither a score nor a card: not a moment.
            if (!scoring && !yellow && !red && type.isEmpty()) continue
            out += Moment(
                clock = d.optJSONObject("clock")?.optString("displayValue")?.takeIf { it.isNotEmpty() },
                type = type,
                teamId = teamId,
                player = d.optJSONArray("athletesInvolved")?.optJSONObject(0)
                    ?.optString("shortName")?.takeIf { it.isNotEmpty() },
                scoring = scoring,
                yellowCard = yellow,
                redCard = red,
                penalty = d.optBoolean("penaltyKick", false),
                ownGoal = ownGoal,
                awayScore = if (scoring) away else null,
                homeScore = if (scoring) home else null,
            )
        }
        return out
    }

    /**
     * Prefer whatever is on nationally; the home and away regional feeds are the
     * wrong answer for at least one of the two fan bases.
     */
    private fun broadcast(comp: JSONObject): String? {
        val casts = comp.optJSONArray("broadcasts")?.objects() ?: return null
        val pick = casts.firstOrNull {
            it.optString("market") == "national" ||
                it.optJSONObject("market")?.optString("type") == "National"
        } ?: casts.firstOrNull() ?: return null
        val names = pick.optJSONArray("names")
        if (names != null) {
            return (0 until names.length()).map { names.optString(it) }
                .filter { it.isNotEmpty() }.joinToString("/").takeIf { it.isNotEmpty() }
        }
        // The team-schedule shape.
        return pick.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotEmpty() }
    }

    /**
     * The delay/suspension names ride on top of an ordinary `state` value rather than
     * replacing it — confirmed live: a rain delay reports `state: "in"`, `completed:
     * false`, `name: "STATUS_RAIN_DELAY"`, which is indistinguishable from a normal
     * live game unless the name is checked first. Without this a delay carries no
     * state change at all: no alert when it starts, and nothing to tell "still
     * delayed" apart from "back and nobody said so."
     */
    private fun state(type: JSONObject?): GameState {
        val name = type?.optString("name").orEmpty().uppercase()
        if (listOf("DELAY", "SUSPEND", "POSTPON", "CANCEL").any { it in name }) {
            return GameState.OFF
        }
        return when (type?.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> if (type.optBoolean("completed", true)) GameState.FINAL else GameState.OFF
            "pre" -> GameState.PRE
            else -> GameState.PRE
        }
    }
}

// -------------------------------------------------------------------- helpers

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun fmtNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
