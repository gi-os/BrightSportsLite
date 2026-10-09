package com.gios.brightsportslite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.gios.brightsportslite.data.Follows
import com.gios.brightsportslite.data.Leagues
import com.gios.brightsportslite.data.SportsRepository
import com.gios.brightsportslite.model.League
import com.gios.brightsportslite.model.TeamRef
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FollowsViewModel(private val follows: Follows) : WheelViewModel<Unit>() {
    val current: StateFlow<Set<String>?> = follows.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggle(key: String) {
        viewModelScope.launch { follows.toggle(key) }
    }
}

/** Every league, grouped the way the full app groups them, with a count of your follows. */
class TeamsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, FollowsViewModel>(sealedActivity) {

    override val viewModelClass = FollowsViewModel::class.java
    override fun createViewModel() = FollowsViewModel(Follows(lightContext.dataStore))

    @Composable
    override fun Content() {
        val follows by viewModel.current.collectAsState()
        Page(
            center = LightTopBarCenter.Text("Teams"),
            left = backButton { goBack(null) },
            wheel = viewModel.wheel,
        ) {
            val set = follows ?: return@Page
            for ((title, leagues) in Leagues.sections) {
                SectionLabel(title.uppercase())
                for (league in leagues) {
                    val count = set.count { it.substringBefore(':') == league.id }
                    ListRow(
                        title = league.name,
                        detail = if (count == 0) league.short else "${league.short} · $count followed",
                        onClick = { navigateTo({ LeagueScreen(it, league) }) },
                    )
                }
            }
        }
    }
}

class LeagueViewModel(
    private val league: League,
    private val follows: Follows,
    private val repo: SportsRepository,
) : WheelViewModel<Unit>() {

    val current: StateFlow<Set<String>?> = follows.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null while loading. */
    private val _teams = MutableStateFlow<List<TeamRef>?>(null)
    val teams: StateFlow<List<TeamRef>?> = _teams.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (_teams.value.isNullOrEmpty()) load()
    }

    fun load() {
        _teams.value = null
        viewModelScope.launch {
            _teams.value = withContext(Dispatchers.IO) { repo.teams(league) }
        }
    }

    fun toggle(key: String) {
        viewModelScope.launch { follows.toggle(key) }
    }
}

/**
 * One league's teams, each with an on/off mark. Leagues whose feed names its one-off
 * games also offer those as a whole category: every championship game, or every
 * all-star and showcase game, whoever plays in it.
 */
class LeagueScreen(sealedActivity: SealedLightActivity, private val league: League) :
    LightScreen<Unit, LeagueViewModel>(sealedActivity) {

    override val viewModelClass = LeagueViewModel::class.java
    override fun createViewModel() = LeagueViewModel(
        league,
        Follows(lightContext.dataStore),
        SportsRepository(lightContext.filesDir),
    )

    @Composable
    override fun Content() {
        val follows by viewModel.current.collectAsState()
        val teams by viewModel.teams.collectAsState()
        Page(
            center = LightTopBarCenter.TwoLineDetail(league.short, league.name),
            left = backButton { goBack(null) },
            wheel = viewModel.wheel,
        ) {
            val set = follows ?: return@Page
            if (league.hasEvents) {
                SectionLabel("WHOLE CATEGORIES")
                val champ = Follows.championshipKey(league.id)
                ToggleRow(
                    title = "Championship games",
                    detail = league.championshipExample,
                    on = champ in set,
                    onClick = { viewModel.toggle(champ) },
                )
                val special = Follows.specialKey(league.id)
                ToggleRow(
                    title = "Special games",
                    detail = league.specialExample,
                    on = special in set,
                    onClick = { viewModel.toggle(special) },
                )
            }
            SectionLabel("TEAMS")
            val list = teams
            when {
                list == null -> EmptyLine("Loading teams…")
                list.isEmpty() -> {
                    EmptyLine("Couldn't load the teams. Check the connection.")
                    ListRow(title = "Try again", onClick = viewModel::load)
                }
                else -> list.forEach { team ->
                    ToggleRow(
                        title = team.displayName,
                        on = team.key in set,
                        onClick = { viewModel.toggle(team.key) },
                    )
                }
            }
        }
    }
}
