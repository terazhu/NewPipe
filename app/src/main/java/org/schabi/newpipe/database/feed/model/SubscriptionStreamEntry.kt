package org.schabi.newpipe.database.feed.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import org.schabi.newpipe.database.feed.model.FeedEntity.Companion.SUBSCRIPTION_ID
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity

data class SubscriptionStreamEntry(
    @ColumnInfo(name = SUBSCRIPTION_ID)
    val subscriptionId: Long,

    @Embedded
    val stream: StreamEntity,

    @ColumnInfo(name = StreamStateEntity.STREAM_PROGRESS_MILLIS)
    val stateProgressMillis: Long?
) {
    fun toStreamWithState() = StreamWithState(stream, stateProgressMillis)
}
