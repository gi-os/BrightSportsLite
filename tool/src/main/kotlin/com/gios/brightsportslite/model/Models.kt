package com.gios.brightsportslite.model

import kotlinx.serialization.Serializable

/**
 * What kind of game this is. Drives how a period is spelled ("Bot 7th" vs "Q3" vs "P2")
 * and which line the score row prints under the two teams.
 */
enum class SportKind { BASEBALL, FOOTBALL, BASKETBALL, HOCKEY, SOCCER }

enum class GameState { PRE, LIVE, FINAL, OFF }

/**
 * Whether a game is a one-off you might follow without following either side.
 * `SHOWCASE` is all-star weekends and neutral-site novelties; `CHAMPIONSHIP` is the
 * Super Bowl, the World Series, MLS Cup and their equivalents.
 */
enum class EventClass { NONE, SHOWCASE, CHAMPIONSHIP }

/** Which provider a league's data comes from. */
enum class Provider { ESPN, STATSAPI, HOCKEYTECH, WPBL }

/**
 * A knockout competition a league's clubs also play in — the Leagues Cup, the U.S. Open
 * Cup. ESPN serves these as separate leagues, but they reuse the parent league's team
 * ids, so a followed club is matched in them without any extra bookkeeping.
 */
data class Cup(
    /** ESPN `sports/soccer/<path>`, e.g. `concacaf.leagues.cup`. */
    val path: String,
    /** Shown in place of the league name on the row: "LEAGUES CUP". */
    val name: String,
)

data class League(
    val id: String,
    val name: String,
    val short: String,
    val kind: SportKind,
    val provider: Provider,
    /** ESPN `sports/<path>` fragment, e.g. `baseball/mlb`. */
    val espnPath: String? = null,
    /**
     * ESPN's `groups` filter, e.g. `"80"` for FBS college football. The scoreboard and
     * standings endpoints honor it (`groups=` on the former, singular `group=` on the
     * latter), but the plain `teams` endpoint silently ignores it. When this is set, the
     * team roster is sourced from the standings tree instead.
     */
    val espnGroup: String? = null,
    /** MLB StatsAPI `sportId`, e.g. 11 for Triple-A. */
    val statsApiSportId: Int? = null,
    /** HockeyTech `client_code`, e.g. `pwhl`. */
    val hockeyTechClient: String? = null,
    /**
     * Whether this league's feed carries recognisable one-off events. Only the ESPN
     * leagues do — MiLB's StatsAPI and the PWHL's HockeyTech feed publish neither the
     * headline nor the season slug the classifier reads.
     */
    val hasEvents: Boolean = false,
    /** Shown under the category toggles so the choice isn't abstract. */
    val championshipExample: String? = null,
    val specialExample: String? = null,
    /** Knockout competitions whose games are folded into this league's feed. */
    val cups: List<Cup> = emptyList(),
)

/** A team the user can follow. Cached per league so the picker works offline. */
data class TeamRef(
    val leagueId: String,
    val teamId: String,
    val displayName: String,
    val short: String,
    val abbrev: String,
) {
    /** Stable key for the follow set. Team ids are only unique within a league. */
    val key: String get() = "$leagueId:$teamId"
}

@Serializable
data class Side(
    val teamId: String,
    val displayName: String,
    val short: String,
    val abbrev: String,
    val score: Int?,
    val record: String? = null,
    /** Runs by inning, points by quarter, goals by period. */
    val lineScore: List<String> = emptyList(),
    val hits: Int? = null,
    val errors: Int? = null,
    /** Poll rank, college football only. ESPN's "unranked" 99 is dropped. */
    val rank: Int? = null,
)

/**
 * One event on a soccer match's timeline: a goal, a card, a penalty. ESPN lists these
 * under `competitions[].details` on the scoreboard itself, so no second request is
 * needed for the match's story.
 */
@Serializable
data class Moment(
    /** "74'", "45'+2'". */
    val clock: String?,
    /** "Goal", "Yellow Card", "Red Card", "Penalty - Scored", "Own Goal". */
    val type: String,
    val teamId: String?,
    /** The player, short form: "B. Saka". */
    val player: String?,
    val scoring: Boolean,
    val yellowCard: Boolean = false,
    val redCard: Boolean = false,
    val penalty: Boolean = false,
    val ownGoal: Boolean = false,
    /** Running score after this moment, filled in by the parser for goals. */
    val awayScore: Int? = null,
    val homeScore: Int? = null,
)

/**
 * Where a live game stands right now, beyond the score. ESPN attaches this to the
 * scoreboard entry while a game is in progress and drops it at the final, so every field
 * is optional. Football fills the top half; baseball the bottom.
 */
@Serializable
data class Situation(
    /** Team id of the side with the ball. */
    val possession: String? = null,
    /** "2nd & 7 at NE 16" — ESPN's own wording. */
    val downDistance: String? = null,
    val shortDownDistance: String? = null,
    /** "NE 16". */
    val spot: String? = null,
    val down: Int? = null,
    val distance: Int? = null,
    val isRedZone: Boolean = false,
    val homeTimeouts: Int? = null,
    val awayTimeouts: Int? = null,
    /** The last play as a sentence: "K.Walker III run for 12 yds for a TD". */
    val lastPlay: String? = null,
    /** "7 plays, 58 yards, 3:41". */
    val drive: String? = null,
    // ---- baseball
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    val onFirst: Boolean = false,
    val onSecond: Boolean = false,
    val onThird: Boolean = false,
    val batter: String? = null,
    val pitcher: String? = null,
    val batterSummary: String? = null,
    val pitcherSummary: String? = null,
)

@Serializable
data class Game(
    val id: String,
    val leagueId: String,
    val state: GameState,
    val startMillis: Long,
    /** Provider's own words: "Bot 7th", "Final/OT", "Postponed", "3:24 - 2nd". */
    val statusDetail: String,
    val period: Int = 0,
    val clock: String? = null,
    val home: Side,
    val away: Side,
    val venue: String? = null,
    val broadcast: String? = null,
    /** Series context: "Game 3 of 7". */
    val note: String? = null,
    /** `STATUS_HALFTIME`, `STATUS_END_PERIOD` and the like. */
    val statusName: String? = null,
    /** "Super Bowl LX", "NHL Winter Classic", "MLS Cup" — what to call this one. */
    val eventTitle: String? = null,
    val eventClass: EventClass = EventClass.NONE,
    /** The competition, when it isn't the league's own: "Leagues Cup". */
    val competition: String? = null,
    /** Live only: possession, down and distance, the count, the last play. */
    val situation: Situation? = null,
    /** The pre-game line as the book writes it: "SEA -3". */
    val odds: String? = null,
    val overUnder: String? = null,
    /** "75° Mostly sunny" — ESPN sends a forecast for outdoor games. */
    val weather: String? = null,
    /** Football's week number. */
    val week: Int? = null,
    /** ESPN's one-line recap of a finished game. */
    val headline: String? = null,
    /** The same recap written out, for the game screen. */
    val recap: String? = null,
    val neutralSite: Boolean = false,
    /** Soccer: goals and cards in match order. Empty elsewhere. */
    val timeline: List<Moment> = emptyList(),
) {
    /**
     * True when the user follows either side, or follows the category this game belongs
     * to. The scores column is this one predicate applied to every fetched game.
     */
    fun involves(teamKeys: Set<String>): Boolean {
        if ("$leagueId:${home.teamId}" in teamKeys) return true
        if ("$leagueId:${away.teamId}" in teamKeys) return true
        return when (eventClass) {
            EventClass.SHOWCASE -> "$leagueId:special" in teamKeys
            EventClass.CHAMPIONSHIP -> "$leagueId:championship" in teamKeys
            EventClass.NONE -> false
        }
    }
}

/** A scoring play from the game summary: what, when, and the score after it. */
data class ScoringPlay(
    val id: String,
    /** "Jelani Woods 13 Yd pass from Geno Smith (Jason Sanders Kick)". */
    val text: String,
    /** "TD", "FG", "HR" — the provider's abbreviation. */
    val kind: String,
    val period: Int,
    val clock: String?,
    /** "Top 3rd": baseball's half-inning, where the period number alone says neither half. */
    val periodLabel: String? = null,
    val teamId: String?,
    val teamAbbrev: String?,
    val awayScore: Int,
    val homeScore: Int,
)
