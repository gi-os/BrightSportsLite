package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.Cup
import com.gios.brightsportslite.model.League
import com.gios.brightsportslite.model.Provider
import com.gios.brightsportslite.model.SportKind

/**
 * Every league the tool knows about, and where its data comes from.
 *
 * Four providers, all keyless public JSON, the same ones the full BrightSports app uses:
 *  - ESPN's site API covers the men's and women's majors, European soccer and college
 *    football.
 *  - MLB's StatsAPI is the only free source for the four full-season MiLB levels.
 *  - The PWHL runs on HockeyTech/LeagueStat, whose feed key is public and baked into
 *    thepwhl.com's own front end.
 *  - The WPBL publishes its season as plain JSON from its own stats service.
 *
 * Team sports only. Formula 1, golf and Grand Slam tennis are left to the full app.
 */
object Leagues {

    val MLB = League(
        id = "mlb", name = "Major League Baseball", short = "MLB",
        kind = SportKind.BASEBALL, provider = Provider.ESPN, espnPath = "baseball/mlb",
        hasEvents = true,
        championshipExample = "World Series",
        specialExample = "All-Star Game, Little League Classic",
    )
    val AAA = League(
        id = "milb-aaa", name = "Triple-A", short = "AAA",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 11,
    )
    val AA = League(
        id = "milb-aa", name = "Double-A", short = "AA",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 12,
    )
    val HIGH_A = League(
        id = "milb-a-plus", name = "High-A", short = "A+",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 13,
    )
    val SINGLE_A = League(
        id = "milb-a", name = "Single-A", short = "A",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 14,
    )
    val NFL = League(
        id = "nfl", name = "National Football League", short = "NFL",
        kind = SportKind.FOOTBALL, provider = Provider.ESPN, espnPath = "football/nfl",
        hasEvents = true,
        championshipExample = "Super Bowl",
        specialExample = "Pro Bowl, the London and Berlin games",
    )
    val NBA = League(
        id = "nba", name = "National Basketball Association", short = "NBA",
        kind = SportKind.BASKETBALL, provider = Provider.ESPN, espnPath = "basketball/nba",
        hasEvents = true,
        championshipExample = "NBA Finals",
        specialExample = "All-Star weekend, NBA Cup final, games abroad",
    )
    val WNBA = League(
        id = "wnba", name = "Women's National Basketball Association", short = "WNBA",
        kind = SportKind.BASKETBALL, provider = Provider.ESPN, espnPath = "basketball/wnba",
        hasEvents = true,
        championshipExample = "WNBA Finals",
        specialExample = "All-Star Game, Commissioner's Cup final",
    )
    val NHL = League(
        id = "nhl", name = "National Hockey League", short = "NHL",
        kind = SportKind.HOCKEY, provider = Provider.ESPN, espnPath = "hockey/nhl",
        hasEvents = true,
        championshipExample = "Stanley Cup Final",
        specialExample = "Winter Classic, Stadium Series, Global Series",
    )
    val PWHL = League(
        id = "pwhl", name = "Professional Women's Hockey League", short = "PWHL",
        kind = SportKind.HOCKEY, provider = Provider.HOCKEYTECH, hockeyTechClient = "pwhl",
    )
    val WPBL = League(
        id = "wpbl", name = "Women's Pro Baseball League", short = "WPBL",
        kind = SportKind.BASEBALL, provider = Provider.WPBL,
    )
    val MLS = League(
        id = "mls", name = "Major League Soccer", short = "MLS",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/usa.1",
        hasEvents = true,
        championshipExample = "MLS Cup",
        specialExample = "MLS All-Stars vs Liga MX",
        // Both reuse the MLS team ids, so a followed club is matched in them with no
        // extra configuration.
        cups = listOf(
            Cup("soccer/concacaf.leagues.cup", "Leagues Cup"),
            Cup("soccer/usa.open", "U.S. Open Cup"),
        ),
    )
    val NWSL = League(
        id = "nwsl", name = "National Women's Soccer League", short = "NWSL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/usa.nwsl",
        hasEvents = true,
        championshipExample = "NWSL Championship",
        specialExample = "no special games published yet",
    )

    val EPL = League(
        id = "epl", name = "English Premier League", short = "EPL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/eng.1",
    )
    val LALIGA = League(
        id = "laliga", name = "Spanish LaLiga", short = "LALIGA",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/esp.1",
    )
    val BUNDESLIGA = League(
        id = "bundesliga", name = "German Bundesliga", short = "BUND",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/ger.1",
    )
    val SERIE_A = League(
        id = "seriea", name = "Italian Serie A", short = "SERIEA",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/ita.1",
    )
    val LIGUE_1 = League(
        id = "ligue1", name = "French Ligue 1", short = "LIGUE1",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/fra.1",
    )
    val UCL = League(
        id = "ucl", name = "UEFA Champions League", short = "UCL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/uefa.champions",
    )
    val UEL = League(
        id = "uel", name = "UEFA Europa League", short = "UEL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/uefa.europa",
    )

    /** FBS only: `groups=80` narrows ESPN's 750-team college football feed. */
    val CFB = League(
        id = "cfb", name = "NCAA Division I FBS Football", short = "CFB",
        kind = SportKind.FOOTBALL, provider = Provider.ESPN,
        espnPath = "football/college-football", espnGroup = "80",
    )

    /** FCS, the division below. Same feed, `groups=81`. */
    val FCS = League(
        id = "fcs", name = "NCAA Division I FCS Football", short = "FCS",
        kind = SportKind.FOOTBALL, provider = Provider.ESPN,
        espnPath = "football/college-football", espnGroup = "81",
    )

    val all: List<League> = listOf(
        MLB, NFL, NBA, NHL, MLS,
        EPL, LALIGA, BUNDESLIGA, SERIE_A, LIGUE_1, UCL, UEL,
        CFB, FCS,
        WNBA, NWSL, PWHL, WPBL,
        AAA, AA, HIGH_A, SINGLE_A,
    )

    /** Grouping used by the team picker, in the order it renders. */
    val sections: List<Pair<String, List<League>>> = listOf(
        "Major" to listOf(MLB, NFL, NBA, NHL, MLS),
        "Soccer" to listOf(EPL, LALIGA, BUNDESLIGA, SERIE_A, LIGUE_1, UCL, UEL),
        "College football" to listOf(CFB, FCS),
        "Women's" to listOf(WNBA, NWSL, PWHL, WPBL),
        "Minor league baseball" to listOf(AAA, AA, HIGH_A, SINGLE_A),
    )

    fun byId(id: String): League? = all.firstOrNull { it.id == id }
}
