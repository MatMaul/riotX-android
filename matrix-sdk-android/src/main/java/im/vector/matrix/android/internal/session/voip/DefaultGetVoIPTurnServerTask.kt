/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.voip

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.homeserver.HomeServerCapabilities
import im.vector.matrix.android.internal.database.model.HomeServerCapabilitiesEntity
import im.vector.matrix.android.internal.database.model.VoIPTurnServerEntity
import im.vector.matrix.android.internal.database.query.getOrCreate
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.Task
import im.vector.matrix.android.internal.util.awaitTransaction
import org.greenrobot.eventbus.EventBus
import java.util.Date
import javax.inject.Inject

internal interface GetVoIPTurnServerTask : Task<Unit, Unit>

internal class DefaultGetVoIPTurnServerTask @Inject constructor(
        private val turnServerAPI: TurnServerAPI,
        private val monarchy: Monarchy,
        private val eventBus: EventBus
) : GetVoIPTurnServerTask {

    override suspend fun execute(params: Unit) {
        var doRequest = false
        monarchy.awaitTransaction { realm ->
            val voIPTurnServerEntity = VoIPTurnServerEntity.getOrCreate(realm)

            doRequest = voIPTurnServerEntity.lastUpdatedTimestamp + MIN_DELAY_BETWEEN_TWO_REQUEST_MILLIS < Date().time
        }

        if (!doRequest) {
            return
        }

        val turnServer = runCatching {
            executeRequest<GetTurnServerResult>(eventBus) {
                apiCall = turnServerAPI.getTurnServer()
            }
        }.getOrNull()

        // TODO Add other call here (get version, etc.)

        insertInDb(turnServer)
    }

    private suspend fun insertInDb(getTurnServerResult: GetTurnServerResult?) {
        monarchy.awaitTransaction { realm ->
            val voIPTurnServerEntity = VoIPTurnServerEntity.getOrCreate(realm)

            voIPTurnServerEntity.username = getTurnServerResult?.username
            voIPTurnServerEntity.password = getTurnServerResult?.password
            voIPTurnServerEntity.uris = getTurnServerResult?.uris
            voIPTurnServerEntity.ttl = getTurnServerResult?.ttl

            voIPTurnServerEntity.lastUpdatedTimestamp = Date().time
        }
    }

    companion object {
        // 8 hours like on Riot Web
        private const val MIN_DELAY_BETWEEN_TWO_REQUEST_MILLIS = 8 * 60 * 60 * 1000
    }
}
