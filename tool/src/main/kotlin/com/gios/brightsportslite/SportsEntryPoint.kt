package com.gios.brightsportslite

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * No server and no push: every provider is polled, and only while the tool is open or
 * the background job runs. The object still has to exist for the SDK's KSP processor to
 * generate its registry.
 */
@EntryPoint
object SportsEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
