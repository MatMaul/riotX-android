package im.vector.matrix.core.internal.sync.data

import com.squareup.moshi.JsonClass
import im.vector.matrix.core.api.events.Event

@JsonClass(generateAdapter = true)
data class RoomSyncAccountData(
        /**
         * List of account data events (array of Event).
         */
        var events: List<Event>? = null
)