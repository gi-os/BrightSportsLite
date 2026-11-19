package com.gios.brightsportslite.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gios.brightsportslite.data.Leagues
import com.gios.brightsportslite.data.Refresh
import com.gios.brightsportslite.data.SportsRepository
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Provider
import com.gios.brightsportslite.model.ScoringPlay
import com.gios.brightsportslite.model.Side
import com.gios.brightsportslite.model.SportKind
import com.gios.brightsportslite.text.CardText
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class GameUi(
    val game: Game,
    /** Null while loading or when it could not be fetched; empty when there is none. */
    val scoring: List<ScoringPlay>? = null,
    val scoringFailed: Boolean = false,
    /** Paragraphs of the full recap, once asked for. */
    val story: List<String>? = null,
    val storyLoading: Boolean = false,
    /** The recap fetch failed, as opposed to ESPN having no recap. */
    val storyFailed: Boolean = false,
    /** When [game] was last fetched or opened. */
    val updatedAt: Long = 0L,
    /** The last refresh of a live game failed, so [game] is stale. */
    val offline: Boolean = false,
)

class GameViewModel(initial: Game, private val repo: SportsRepository) : WheelViewModel<Unit>() {

    private val _ui = MutableStateFlow(GameUi(initial, updatedAt = System.currentTimeMillis()))
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    private val league = Leagues.byId(initial.leagueId)
    private var loop: Job? = null
    private var refreshing = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loop?.cancel()
        loop = viewModelScope.launch {
            loadScoring()
            // A finished or called-off game does not change; only an unfinished one is
            // asked again, at the same once-a-minute floor as the scores screen.
            while (isActive && _ui.value.game.state.let { it == GameState.LIVE || it == GameState.PRE }) {
                delay(Refresh.nextDelay(listOf(_ui.value.game), System.currentTimeMillis()))
                refreshGame()
            }
        }
    }

    /** The refresh button. A tap inside twenty seconds of the last good fetch is not sent. */
    fun refreshNow() {
        val state = _ui.value
        if (refreshing) return
        if (!state.offline && System.currentTimeMillis() - state.updatedAt < Refresh.MANUAL_MIN_MILLIS) return
        viewModelScope.launch { refreshGame() }
    }

    /** Fetches the game again. A failure marks the screen offline and leaves the old score up. */
    private suspend fun refreshGame() {
        refreshing = true
        try {
            val g = _ui.value.game
            val fresh = withContext(Dispatchers.IO) {
                repo.game(g.leagueId, g.id, System.currentTimeMillis(), ZoneId.systemDefault())
            }
            if (fresh == null) {
                _ui.update { it.copy(offline = true) }
                return
            }
            val moved = fresh.home.score != g.home.score || fresh.away.score != g.away.score ||
                fresh.state != g.state
            _ui.update { it.copy(game = fresh, offline = false, updatedAt = System.currentTimeMillis()) }
            if (moved) loadScoring()
        } finally {
            refreshing = false
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loop?.cancel()
    }

    override fun onAppPause() {
        super.onAppPause()
        loop?.cancel()
    }

    private suspend fun loadScoring() {
        val l = league ?: return
        val g = _ui.value.game
        if (!SportsRepository.hasScoringSummary(l) || g.state == GameState.PRE) return
        val plays = withContext(Dispatchers.IO) { repo.scoring(l, g.id, final = g.state == GameState.FINAL) }
        _ui.update { it.copy(scoring = plays ?: it.scoring, scoringFailed = plays == null && it.scoring == null) }
    }

    fun loadStory() {
        val l = league ?: return
        if (_ui.value.storyLoading || _ui.value.story != null) return
        _ui.update { it.copy(storyLoading = true, storyFailed = false) }
        viewModelScope.launch {
            val story = withContext(Dispatchers.IO) { repo.recap(l, _ui.value.game.id) }
            _ui.update { it.copy(story = story, storyLoading = false, storyFailed = story == null) }
        }
    }
}

/** One game: the score, where it stands, how it got there, and where and when it is. */
class GameScreen(sealedActivity: SealedLightActivity, private val game: Game) :
    LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass = GameViewModel::class.java
    override fun createViewModel() = GameViewModel(game, SportsRepository(lightContext.filesDir))

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val g = ui.game
        val zone = ZoneId.systemDefault()
        val league = Leagues.byId(g.leagueId)
        val kind = league?.kind ?: SportKind.BASEBALL
        Page(
            center = LightTopBarCenter.TwoLineDetail(
                "${g.away.abbrev} at ${g.home.abbrev}",
                if (ui.offline) "Offline, updated " + CardText.time(ui.updatedAt, zone) else CardText.statusLine(g, zone),
            ),
            left = backButton { goBack(null) },
            bottom = if (g.state == GameState.LIVE || g.state == GameState.PRE) listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = viewModel::refreshNow,
                    contentDescription = "Refresh",
                ),
            ) else null,
            wheel = viewModel.wheel,
        ) {
            Score(g)
            SituationBlock(g, kind)

            // ---- the recap, final games only
            g.recap?.let { lead ->
                SectionLabel("RECAP")
                val story = ui.story
                if (story.isNullOrEmpty()) {
                    LightText(text = lead, variant = LightTextVariant.Paragraph)
                } else {
                    story.forEach {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                if (g.state == GameState.FINAL && league?.provider == Provider.ESPN) {
                    when {
                        story == null && ui.storyFailed && !ui.storyLoading -> {
                            LightText(text = "Could not load the recap", variant = LightTextVariant.Copy)
                            ListRow(title = "Try again", onClick = viewModel::loadStory)
                        }
                        story == null && !ui.storyLoading ->
                            ListRow(title = "Read the full recap", onClick = viewModel::loadStory)
                        story == null -> DetailLine("Loading…")
                        story.isEmpty() -> DetailLine("No full recap for this game.")
                    }
                }
            }

            // ---- line score
            val rows = CardText.lineScoreRows(g, kind)
            if (rows.isNotEmpty()) {
                SectionLabel(CardText.lineScoreTitle(kind).uppercase())
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    rows.forEachIndexed { i, row ->
                        LightText(
                            text = row,
                            variant = LightTextVariant.Detail,
                            monospace = true,
                            lighten = i == 0,
                            maxLines = 1,
                        )
                    }
                }
            }

            // ---- how the score happened
            if (kind == SportKind.SOCCER && g.timeline.isNotEmpty()) {
                SectionLabel("TIMELINE")
                for (m in g.timeline) {
                    val team = if (m.teamId == g.home.teamId) g.home.abbrev else if (m.teamId == g.away.teamId) g.away.abbrev else null
                    ListRow(
                        title = CardText.momentLine(m),
                        detail = listOfNotNull(
                            team,
                            if (m.scoring && m.awayScore != null) "${m.awayScore}–${m.homeScore}" else null,
                        ).joinToString(" · ").takeIf { it.isNotEmpty() },
                    )
                }
            }
            if (league != null && g.state != GameState.PRE && SportsRepository.hasScoringSummary(league)) {
                SectionLabel("SCORING")
                val plays = ui.scoring
                when {
                    plays == null && ui.scoringFailed -> DetailLine("Couldn't load the scoring summary.")
                    plays == null -> DetailLine("Loading…")
                    plays.isEmpty() -> DetailLine(
                        if (g.state == GameState.LIVE) "Nobody has scored yet." else "No scoring summary for this game.",
                    )
                    else -> plays.forEach { p ->
                        ListRow(
                            title = "${p.kind.uppercase().ifEmpty { "•" }}  ${p.text}",
                            detail = listOf(CardText.scoringMoment(p, kind), "${p.awayScore}–${p.homeScore}")
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                        )
                    }
                }
            }

            // ---- details
            SectionLabel("DETAILS")
            g.note?.let { ListRow(title = "Series", detail = it) }
            ListRow(
                title = CardText.startLabel(kind),
                detail = CardText.dayDate(g.startMillis, zone) + ", " + CardText.time(g.startMillis, zone),
            )
            g.venue?.let { ListRow(title = "Venue", detail = it) }
            g.broadcast?.let { ListRow(title = "TV", detail = it) }
            if (g.state == GameState.PRE) {
                g.odds?.let { ListRow(title = "Line", detail = it) }
                g.overUnder?.let { ListRow(title = "Over/under", detail = it) }
            }
            g.weather?.let { ListRow(title = "Weather", detail = it) }
            g.away.record?.let { ListRow(title = g.away.displayName, detail = it) }
            g.home.record?.let { ListRow(title = g.home.displayName, detail = it) }
        }
    }
}

@Composable
private fun Score(g: Game) {
    val showScore = g.state == GameState.LIVE || g.state == GameState.FINAL
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp())) {
        ScoreLine(g.away, showScore, dim = g.state == GameState.FINAL && (g.away.score ?: 0) < (g.home.score ?: 0))
        ScoreLine(g.home, showScore, dim = g.state == GameState.FINAL && (g.home.score ?: 0) < (g.away.score ?: 0))
    }
}

@Composable
private fun ScoreLine(side: Side, showScore: Boolean, dim: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = CardText.teamName(side.rank, side.short.ifEmpty { side.displayName }),
            variant = LightTextVariant.Heading,
            lighten = dim,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (showScore) {
            LightText(
                text = side.score?.toString() ?: "–",
                variant = LightTextVariant.Heading,
                lighten = dim,
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/** Live only: the count or the down, who is up, and the last play. */
@Composable
private fun SituationBlock(g: Game, kind: SportKind) {
    if (g.state != GameState.LIVE) return
    val s = g.situation ?: return
    CardText.situationLine(g, kind)?.let { DetailLine(it, lighten = false) }
    if (kind == SportKind.BASEBALL) {
        s.batter?.let { DetailLine("At bat: $it" + (s.batterSummary?.let { sum -> " ($sum)" } ?: "")) }
        s.pitcher?.let { DetailLine("Pitching: $it" + (s.pitcherSummary?.let { sum -> " ($sum)" } ?: "")) }
    }
    s.lastPlay?.let {
        LightText(
            text = it,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
    s.drive?.let { DetailLine(it) }
}
