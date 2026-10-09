package com.gios.brightsportslite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gios.brightsportslite.data.Feed
import com.gios.brightsportslite.data.Follows
import com.gios.brightsportslite.data.Refresh
import com.gios.brightsportslite.data.ScoreCache
import com.gios.brightsportslite.data.ScoreSnapshot
import com.gios.brightsportslite.data.SportsRepository
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.text.CardText
import com.gios.brightsportslite.work.scheduleRefresh
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class ScoresUi(
    /** Null until the follow set has been read. */
    val follows: Set<String>? = null,
    val games: List<Game> = emptyList(),
    /** The follow set [games] was fetched for. */
    val fetchedFor: Set<String> = emptySet(),
    val fetchedAt: Long = 0L,
    val loading: Boolean = false,
    /** The last attempt reached no provider at all. */
    val offline: Boolean = false,
)

class ScoresViewModel(
    private val follows: Follows,
    private val repo: SportsRepository,
    private val cache: ScoreCache,
) : WheelViewModel<Unit>() {

    private val _ui = MutableStateFlow(ScoresUi())
    val ui: StateFlow<ScoresUi> = _ui.asStateFlow()

    private val fetchLock = Mutex()
    private var loop: Job? = null
    private var visible = false

    init {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { cache.read() }
            if (snapshot != null) {
                _ui.update {
                    if (it.fetchedAt >= snapshot.fetchedAt) it
                    else it.copy(
                        games = snapshot.games,
                        fetchedFor = snapshot.follows.toSet(),
                        fetchedAt = snapshot.fetchedAt,
                    )
                }
            }
            follows.flow.collect { set -> _ui.update { it.copy(follows = set) } }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        visible = true
        startLoop()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stop()
    }

    override fun onAppPause() {
        super.onAppPause()
        stop()
    }

    private fun stop() {
        visible = false
        loop?.cancel()
        loop = null
    }

    /**
     * Fetches on arrival when the cache is stale or was made for other teams, then again
     * on [Refresh.nextDelay] for as long as the screen is showing.
     */
    private fun startLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            val set = follows.current()
            val now = System.currentTimeMillis()
            val state = _ui.value
            if (state.fetchedFor != set || !Refresh.isFresh(state.fetchedAt, state.games, now)) {
                fetch(set)
            }
            while (isActive) {
                delay(Refresh.nextDelay(_ui.value.games, System.currentTimeMillis()))
                fetch(follows.current())
            }
        }
    }

    /** The refresh button. A second tap inside twenty seconds is not sent. */
    fun refreshNow() {
        viewModelScope.launch {
            val set = follows.current()
            val state = _ui.value
            val recent = System.currentTimeMillis() - state.fetchedAt < Refresh.MANUAL_MIN_MILLIS
            if (recent && state.fetchedFor == set && !state.offline) return@launch
            fetch(set)
            if (visible) startLoop()
        }
    }

    private suspend fun fetch(set: Set<String>) = fetchLock.withLock {
        if (set.isEmpty()) {
            _ui.update { it.copy(games = emptyList(), fetchedFor = set, loading = false, offline = false) }
            return@withLock
        }
        _ui.update { it.copy(loading = true) }
        val now = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            repo.followedGames(set, now, ZoneId.systemDefault())
        }
        if (result.offline) {
            _ui.update { it.copy(loading = false, offline = true) }
            return@withLock
        }
        withContext(Dispatchers.IO) {
            cache.write(ScoreSnapshot(now, set.sorted(), result.games))
        }
        _ui.update {
            it.copy(
                games = result.games,
                fetchedFor = set,
                fetchedAt = now,
                loading = false,
                offline = false,
            )
        }
    }
}

/**
 * One column of scores for the followed teams: live first, then today and the week
 * ahead, then the last few days of results. Nothing from teams you do not follow.
 */
@InitialScreen
class ScoresScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ScoresViewModel>(sealedActivity) {

    override val viewModelClass = ScoresViewModel::class.java
    override fun createViewModel() = ScoresViewModel(
        follows = Follows(lightContext.dataStore),
        repo = SportsRepository(lightContext.filesDir),
        cache = ScoreCache(lightContext.filesDir),
    )

    override fun willShow() {
        scheduleRefresh(lightContext)
    }

    private fun openTeams() {
        navigateTo(::TeamsScreen)
    }

    private fun openGame(game: Game) {
        navigateTo({ GameScreen(it, game) })
    }

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val zone = ZoneId.systemDefault()
        val follows = ui.follows
        Page(
            center = LightTopBarCenter.TwoLineDetail("Scores", statusLine(ui, zone)),
            right = LightBarButton.Text(text = "TEAMS", onClick = ::openTeams),
            bottom = if (follows.isNullOrEmpty()) null else listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = viewModel::refreshNow,
                    contentDescription = "Refresh",
                ),
            ),
            wheel = viewModel.wheel,
        ) {
            when {
                follows == null -> Unit
                follows.isEmpty() -> {
                    EmptyLine("You don't follow any teams yet.")
                    ListRow(title = "Pick teams", onClick = ::openTeams)
                }
                else -> {
                    val now = System.currentTimeMillis()
                    val mine = ui.games.filter { it.involves(follows) }
                    val sections = Feed.build(
                        mine, now, zone,
                        backDays = SportsRepository.BACK_DAYS,
                        aheadDays = SportsRepository.AHEAD_DAYS,
                    )
                    if (sections.isEmpty()) {
                        EmptyLine(
                            when {
                                ui.loading && ui.fetchedAt == 0L -> "Loading scores…"
                                ui.offline && ui.fetchedAt == 0L -> "No connection. Scores will load when you're back online."
                                else -> "No games for your teams in the last " +
                                    "${SportsRepository.BACK_DAYS} days or the next ${SportsRepository.AHEAD_DAYS}."
                            },
                        )
                    }
                    val shown = sections.flatMap { it.items }.map { (it as Feed.Item.GameItem).game }
                    Feed.recordLine(shown, follows)?.let { DetailLine(it) }
                    for (section in sections) {
                        SectionLabel(section.title)
                        for (item in section.items) {
                            val game = (item as Feed.Item.GameItem).game
                            GameRow(game, zone) { openGame(game) }
                        }
                    }
                }
            }
        }
    }

    private fun statusLine(ui: ScoresUi, zone: ZoneId): String = when {
        ui.loading -> "Updating…"
        ui.fetchedAt == 0L -> if (ui.offline) "Offline" else " "
        ui.offline -> "Offline · " + CardText.time(ui.fetchedAt, zone)
        else -> "Updated " + CardText.time(ui.fetchedAt, zone)
    }
}

/** One game: status on top, away over home with the scores, one line of context. */
@Composable
fun GameRow(game: Game, zone: ZoneId, onClick: () -> Unit) {
    val live = game.state == GameState.LIVE
    val final = game.state == GameState.FINAL
    val homeScore = game.home.score ?: 0
    val awayScore = game.away.score ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = CardText.statusLine(game, zone),
            variant = LightTextVariant.Detail,
            lighten = !live,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val showScore = game.state == GameState.LIVE || game.state == GameState.FINAL
        // In a finished game the loser drops to grey: a result at a glance, without colour.
        TeamLine(game.away, showScore, dim = final && awayScore < homeScore)
        TeamLine(game.home, showScore, dim = final && homeScore < awayScore)
        CardText.thirdLine(game)?.let { DetailLine(it, lighten = !live) }
    }
    Rule()
}

@Composable
private fun TeamLine(side: Side, showScore: Boolean, dim: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = CardText.teamName(side.rank, side.displayName.ifEmpty { side.short }),
            variant = LightTextVariant.Copy,
            lighten = dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showScore) {
            LightText(
                text = side.score?.toString() ?: "–",
                variant = LightTextVariant.Copy,
                lighten = dim,
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
