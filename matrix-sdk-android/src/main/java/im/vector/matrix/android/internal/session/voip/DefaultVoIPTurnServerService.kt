/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.voip

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.voip.TurnServer
import im.vector.matrix.android.api.session.voip.VoIPTurnServerService
import im.vector.matrix.android.internal.database.mapper.HomeServerCapabilitiesMapper
import im.vector.matrix.android.internal.database.model.HomeServerCapabilitiesEntity
import im.vector.matrix.android.internal.database.model.VoIPTurnServerEntity
import im.vector.matrix.android.internal.database.query.get
import io.realm.Realm
import javax.inject.Inject

internal class DefaultVoIPTurnServerService @Inject constructor(private val monarchy: Monarchy) : VoIPTurnServerService {

    override fun getTurnServer(): TurnServer {
        return Realm.getInstance(monarchy.realmConfiguration).use { realm ->
            VoIPTurnServerEntity.get(realm)?.let {
                TurnServer(username = it.username, password = it.password, uris = it.uris, ttl = it.ttl)
            }
        }
                ?: TurnServer(null, null, null, null)
    }
}
