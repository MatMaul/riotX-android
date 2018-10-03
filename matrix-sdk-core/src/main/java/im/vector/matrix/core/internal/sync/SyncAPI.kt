package im.vector.matrix.core.internal.sync

import im.vector.matrix.core.internal.network.NetworkConstants
import im.vector.matrix.core.internal.sync.data.SyncResponse
import kotlinx.coroutines.Deferred
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap

interface SyncAPI {

    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "sync")
    fun sync(@QueryMap params: Map<String, Any>): Deferred<Response<SyncResponse>>

}