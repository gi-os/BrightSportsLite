package com.gios.brightsportslite.data

import com.gios.brightsportslite.model.Game
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last fetched column of scores, on disk, so the screen opens on numbers instead of
 * a spinner. Written by the screen and by the background job; read by the screen.
 */
@Serializable
data class ScoreSnapshot(
    val fetchedAt: Long = 0L,
    /** The follow set the games were fetched for. A different set means fetch again. */
    val follows: List<String> = emptyList(),
    val games: List<Game> = emptyList(),
)

class ScoreCache(filesDir: File) {

    private val file = File(filesDir, "scores.json")

    fun read(): ScoreSnapshot? = runCatching {
        if (!file.exists()) null else json.decodeFromString(ScoreSnapshot.serializer(), file.readText())
    }.getOrNull()

    fun write(snapshot: ScoreSnapshot) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ScoreSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
