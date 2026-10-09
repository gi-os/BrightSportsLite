package com.gios.brightsportslite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The followed teams, as a set of `leagueId:teamId` keys in the tool's shared DataStore.
 * A whole category is followed the same way, as `leagueId:championship` or
 * `leagueId:special`, which is what [com.gios.brightsportslite.model.Game.involves] reads.
 */
class Follows(private val dataStore: DataStore<Preferences>) {

    private val key = stringSetPreferencesKey("follows")

    val flow: Flow<Set<String>> = dataStore.data.map { it[key].orEmpty() }

    suspend fun current(): Set<String> = flow.first()

    suspend fun toggle(followKey: String) {
        dataStore.edit { prefs ->
            val now = prefs[key].orEmpty()
            prefs[key] = if (followKey in now) now - followKey else now + followKey
        }
    }

    companion object {
        /** League ids that have at least one follow — the only ones worth fetching. */
        fun leagueIds(follows: Set<String>): List<String> =
            follows.mapNotNull { it.substringBefore(':').takeIf { id -> id.isNotEmpty() } }.distinct()

        fun championshipKey(leagueId: String) = "$leagueId:${SpecialEvents.SUFFIX_CHAMPIONSHIP}"

        fun specialKey(leagueId: String) = "$leagueId:${SpecialEvents.SUFFIX_SPECIAL}"
    }
}
