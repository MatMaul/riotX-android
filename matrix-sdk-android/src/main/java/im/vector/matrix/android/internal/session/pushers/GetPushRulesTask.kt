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
package im.vector.matrix.android.internal.session.pushers

import im.vector.matrix.android.api.pushrules.rest.GetPushRulesResponse
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.Task
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal interface GetPushRulesTask : Task<GetPushRulesTask.Params, Unit> {
    data class Params(val scope: String)
}

/**
 * We keep this task, but it should not be used anymore, the push rules comes from the sync response
 */
internal class DefaultGetPushRulesTask @Inject constructor(
        private val pushRulesApi: PushRulesApi,
        private val savePushRulesTask: SavePushRulesTask,
        private val eventBus: EventBus
) : GetPushRulesTask {

    override suspend fun execute(params: GetPushRulesTask.Params) {
        val response = executeRequest<GetPushRulesResponse>(eventBus) {
            apiCall = pushRulesApi.getAllRules()
        }

        savePushRulesTask.execute(SavePushRulesTask.Params(response))
    }
}
