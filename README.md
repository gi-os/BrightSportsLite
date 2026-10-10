# BrightSports Lite

> **This is the Lite version.** BrightSports Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [BrightSports](https://github.com/gi-os/BrightSports) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.lightsports/)).

Scores for the teams you follow, on the Light Phone III, built as an official Light SDK tool.
Launcher label **BrightSports Lite**, tool id `com.gios.brightsportslite`.

This is the SDK-compatible cut of [BrightSports](https://github.com/gi-os/BrightSports), a
sideloaded app. The Light SDK limits which Android APIs and libraries a tool may use, so this
version keeps the core of the original (follow your teams, read one column of scores, open a
game) and leaves out the parts the SDK cannot support. Each one is listed below with the reason.

## What it does

**Scores.** One column, followed teams only. Live games come first, then today, tomorrow and
the rest of the week, one section per day, then the last three days of results, newest first.
Each row has the league (or the event, like "SUPER BOWL LX", or the cup, like "LEAGUES CUP"),
where the game stands ("Q2 3:24", "BOT 7TH", "SUN 1:00 PM", "FINAL/OT"), both teams with the
score, and one line of context: down and distance in football, the count, outs and runners in
baseball, the betting line and forecast before a game, ESPN's headline after it. In a finished
game the loser is drawn in grey. The list ends: three days back and seven days ahead, nothing
more.

**Game.** Tap a row for the game: both teams and the score, the live situation (batter and
pitcher, last play, the drive), the recap for a finished game with the full story one tap
further, the line score by inning, quarter, period or half, the scoring summary (ESPN leagues
except basketball) or the goals and cards for soccer, then start time, venue, TV, the line,
weather and both records.

**Teams.** Leagues grouped as in the full app. Open one to tick teams on and off. The ESPN
majors and NWSL/MLS also offer two whole categories, **Championship games** and **Special
games** (all-star games, outdoor games, games abroad), for following those whoever plays.
Team lists are cached for a week, so the picker still works with no signal.

**Refreshing.** The scores screen fetches when it opens if what it has is stale, then every
minute while a followed game is live or about to start, and every ten minutes otherwise. It
stops when the screen is not showing. The refresh button at the bottom fetches on demand, but
not more than once every twenty seconds. A `LightWork` job refreshes the cache every half hour
in the background so the screen opens on recent numbers. It never notifies.

**The wheel** scrolls every screen. The wheel's keycodes (`WHEEL_CW`/`WHEEL_CCW`, scancodes
20/19 on the Pixart sensor) are read in `LightViewModel.onKeyDown`.

### Leagues and data sources

| | |
| --- | --- |
| Majors | MLB, NFL, NBA, NHL, MLS (with Leagues Cup and U.S. Open Cup games) |
| Soccer | EPL, LaLiga, Bundesliga, Serie A, Ligue 1, Champions League, Europa League |
| College football | FBS and FCS |
| Women's | WNBA, NWSL, PWHL, WPBL |
| Minor league baseball | Triple-A, Double-A, High-A, Single-A |

The same four keyless public JSON sources the full app uses, with no account or key:

- ESPN's site API (`site.api.espn.com/apis/site/v2/sports/.../scoreboard`, `teams`, `summary`,
  and `apis/v2/.../standings` for the college football rosters).
- MLB StatsAPI (`statsapi.mlb.com/api/v1/schedule` and `teams`) for the minor leagues.
- HockeyTech/LeagueStat (`lscluster.hockeytech.com`, the public key thepwhl.com uses) for the PWHL.
- The WPBL's own stats service (`stats.womensprobaseballleague.com/v1/games` and `teams`).

The four parsers, the special-game classifier, the timestamp parser and the feed grouping are
ported from BrightSports nearly line for line, along with their tests, including the
workarounds for ESPN's broken date-range queries and its college football limit cliff. The full
app reads JSON through Android's built-in `org.json`; here a small stand-in with the same
`opt*` behavior (`json/OrgJson.kt`) sits on kotlinx.serialization, so the parsers run unchanged
in plain JVM unit tests.

## What was dropped, and why

| Feature in BrightSports | Why it is not here |
| --- | --- |
| Score alerts, the live ticker, the alert box over other apps, buzz patterns, spoiler delay | The SDK gives tools no notification API (`NotificationManager` comes from `getSystemService`, which is blocked). The overlay needs `SYSTEM_ALERT_WINDOW`, the exact-alarm poll needs `AlarmManager` from `android.app`, and the ticker is a foreground service. None of these are allowed. |
| Live push over the BasilNet relay | Only useful for alerts, which are gone, and it depends on a private server. |
| BrightControl integration (lock-face card, alert ownership) | Talks to another app through broadcasts and intents, which are blocked. |
| Home screen widgets | Tools cannot publish widgets. |
| Shake to report a glitch | Needs `SensorManager` from `getSystemService`. |
| Team crests and the condensed score font | Left out to keep the tool small and in Light's own type. |
| Formula 1, PGA golf, Grand Slam tennis | Field events and players rather than two teams. Left to the full app to keep this one to one shape of game. |
| Standings, team season pages, search across every league | Out of scope for a lite cut. Search across non-followed games would also cut against Light's guidance against feeds you did not ask for. |
| Play-by-play, the drawn football field and diamond, celebrations | The play-by-play wanted a 15-second refresh; this tool will not refresh faster than once a minute. The rest is decoration. |
| Silencing a followed team | Only meaningful for alerts. |

## Build

You need JDK 17 and the Android SDK (platform 36).

```sh
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug
```

Parsing, formatting and refresh timing live in `tool/src/main/kotlin/com/gios/brightsportslite/`
under `data/`, `json/` and `text/`, with no Android imports, and are tested in `tool/src/test/`.
CI (`.github/workflows/build-tool.yml`) runs the tests, lint and the debug build on every push
with the same flags as Light's tool builder. It publishes nothing.

To install on a phone or emulator running the Light SDK server, see `./gradlew :tool:uploadTool`
in `tool/build.gradle.kts`.

## Permissions

- `INTERNET`, to fetch scores and team lists.
- `ACCESS_NETWORK_STATE`, which the network stack uses to tell no signal from a failed request.

## Privacy

No account, no analytics, no tracking. The tool sends ordinary GET requests to the four score
providers above, one per followed league, and nothing else. Your list of teams never leaves
the phone: it asks for a league's whole scoreboard and filters it on the device.

## Tool Library submission notes

- The tool id, label and version are in `tool/lighttool.toml`. Bump `versionCode` for every
  submission.
- Everything is in the `tool` module. `sdk/`, `plugin/` and `lint-rules/` are upstream and
  unmodified, so this repository can be rebased onto
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk).
- Dependencies are only what `:sdk:client` already provides: OkHttp (through ktor's OkHttp
  engine), kotlinx.serialization, DataStore, WorkManager through `LightWork`, and Compose.
- No reflection, no blocked imports, no custom manifest.
- Intentionality: no feed of games for teams you don't follow, a list that ends, and no
  automatic refresh faster than once a minute.

## Credits

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT); see `LICENSE`.
The parsers and feed logic are ported from [gi-os/BrightSports](https://github.com/gi-os/BrightSports).
Scores come from ESPN, MLB StatsAPI, HockeyTech and the WPBL; none of them is affiliated with
this tool.
