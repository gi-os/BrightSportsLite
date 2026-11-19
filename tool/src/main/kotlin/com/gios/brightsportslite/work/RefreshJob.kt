package com.gios.brightsportslite.work

import com.gios.brightsportslite.data.Follows
import com.gios.brightsportslite.data.Refresh
import com.gios.brightsportslite.data.ScoreCache
import com.gios.brightsportslite.data.ScoreSnapshot
import com.gios.brightsportslite.data.SportsRepository
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

const val REFRESH_JOB = "refresh-scores"

/**
 * Keeps the scores cache warm, so opening the tool shows numbers from the last half hour
 * rather than a blank column while the network wakes up. It never alerts or buzzes:
 * there is no notification API for tools, and this is not a ticker.
 */
@LightJob("refresh-scores")
val refreshScores: LightJobHandler = { ctx, _ ->
    val follows = Follows(ctx.dataStore).current()
    if (follows.isEmpty()) {
        LightJobResult.Success(outputFilePath = null, message = "Nothing followed")
    } else {
        val repo = SportsRepository(ctx.filesDir)
        val now = System.currentTimeMillis()
        val cache = ScoreCache(ctx.filesDir)
        val last = cache.read()
        val fetch = repo.followedGames(
            follows, now, ZoneId.systemDefault(),
            previous = last?.games.orEmpty(),
        )
        repo.trimCache(now)
        if (fetch.offline) {
            LightJobResult.Retry
        } else {
            // A partial fetch carries old games for the failed leagues, so it keeps the last
            // snapshot's time instead of stamping them fresh, and the next open fetches again.
            val stamp = if (fetch.partial) last?.fetchedAt ?: 0L else now
            cache.write(ScoreSnapshot(stamp, follows.sorted(), fetch.games))
            LightJobResult.Success(outputFilePath = null, message = "${fetch.games.size} games")
        }
    }
}

/** Every half hour, replacing any earlier schedule so there is only ever one. */
fun scheduleRefresh(context: SealedLightContext) {
    LightWork.enqueuePeriodic(context, REFRESH_JOB, repeatInterval = Refresh.BACKGROUND_MINUTES.minutes)
}
