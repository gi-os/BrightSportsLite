package com.gios.brightsportslite

import com.gios.brightsportslite.data.ScoreCache
import com.gios.brightsportslite.data.ScoreSnapshot
import com.gios.brightsportslite.json.JSONArray
import com.gios.brightsportslite.json.JSONObject
import com.gios.brightsportslite.model.Game
import com.gios.brightsportslite.model.GameState
import com.gios.brightsportslite.model.Side
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parsers lean on org.json's leniency; these pin the parts they use. */
class OrgJsonTest {

    private val o = JSONObject(
        """{"s":"7","n":3,"d":2.5,"b":"true","t":true,"z":null,"o":{"k":"v"},"a":[{"x":1},"y"]}""",
    )

    @Test
    fun `missing keys are never errors`() {
        assertEquals("", o.optString("nope"))
        assertEquals(-1, o.optInt("nope", -1))
        assertTrue(o.optDouble("nope").isNaN())
        assertFalse(o.optBoolean("nope", false))
        assertNull(o.optJSONObject("nope"))
        assertNull(o.optJSONArray("nope"))
    }

    @Test
    fun `numbers and strings convert into each other`() {
        assertEquals(7, o.optInt("s"))
        assertEquals("3", o.optString("n"))
        assertEquals(2, o.optInt("d"))
        assertEquals(2.5, o.optDouble("d"))
        assertTrue(o.optBoolean("b"))
        assertTrue(o.optBoolean("t"))
    }

    @Test
    fun `a json null reads as absent`() {
        assertEquals("", o.optString("z"))
        assertTrue(o.isNull("z"))
        assertTrue(o.has("z"))
    }

    @Test
    fun `nested objects and arrays`() {
        assertEquals("v", o.optJSONObject("o")?.optString("k"))
        val a = o.optJSONArray("a")!!
        assertEquals(2, a.length())
        assertEquals(1, a.optJSONObject(0)?.optInt("x"))
        assertNull(a.optJSONObject(1))
        assertEquals("y", a.optString(1))
    }

    @Test
    fun `built objects round-trip`() {
        val built = JSONObject().put("events", JSONArray().put(JSONObject().put("id", "1")))
        val back = JSONObject(built.toString())
        assertEquals("1", back.getJSONArray("events").getJSONObject(0).getString("id"))
    }

    @Test
    fun `the score cache survives a round trip`() {
        val dir = Files.createTempDirectory("scores").toFile()
        val cache = ScoreCache(dir)
        assertNull(cache.read())
        val game = Game(
            id = "1", leagueId = "mlb", state = GameState.FINAL, startMillis = 5L, statusDetail = "Final",
            home = Side("1", "H", "H", "H", 3), away = Side("2", "A", "A", "A", 1),
        )
        cache.write(ScoreSnapshot(42L, listOf("mlb:1"), listOf(game)))
        val read = cache.read()!!
        assertEquals(42L, read.fetchedAt)
        assertEquals(listOf(game), read.games)
        dir.deleteRecursively()
    }
}
