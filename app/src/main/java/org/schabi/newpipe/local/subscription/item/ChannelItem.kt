package org.schabi.newpipe.local.subscription.item

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.xwray.groupie.GroupieViewHolder
import com.xwray.groupie.Item
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.image.CoilHelper

class ChannelItem(
    private val infoItem: ChannelInfoItem,
    private val subscriptionId: Long = -1L,
    var itemVersion: ItemVersion = ItemVersion.NORMAL,
    private val latestStreams: List<StreamWithState> = emptyList(),
    var gesturesListener: OnClickGesture<ChannelInfoItem>? = null
) : Item<GroupieViewHolder>() {
    var streamClickListener: ((StreamWithState) -> Unit)? = null

    override fun getId(): Long = if (subscriptionId == -1L) super.getId() else subscriptionId

    enum class ItemVersion { NORMAL, MINI, GRID, LATEST }

    override fun getLayout(): Int = when (itemVersion) {
        ItemVersion.NORMAL -> R.layout.list_channel_item
        ItemVersion.MINI -> R.layout.list_channel_mini_item
        ItemVersion.GRID -> R.layout.list_channel_grid_item
        ItemVersion.LATEST -> R.layout.subscription_channel_latest_item
    }

    override fun bind(viewHolder: GroupieViewHolder, position: Int) {
        val itemTitleView = viewHolder.root.findViewById<TextView>(R.id.itemTitleView)
        val itemAdditionalDetails = viewHolder.root.findViewById<TextView>(R.id.itemAdditionalDetails)
        val itemChannelDescriptionView = viewHolder.root.findViewById<TextView>(R.id.itemChannelDescriptionView)
        val itemThumbnailView = viewHolder.root.findViewById<ImageView>(R.id.itemThumbnailView)

        itemTitleView.text = infoItem.name
        itemAdditionalDetails.text = getDetailLine(viewHolder.root.context)
        if (itemVersion == ItemVersion.NORMAL) {
            itemChannelDescriptionView.text = infoItem.description
        }

        CoilHelper.loadAvatar(itemThumbnailView, infoItem.thumbnails)

        gesturesListener?.run {
            val channelTarget = if (itemVersion == ItemVersion.LATEST) {
                viewHolder.root.findViewById(R.id.channelHeader)
            } else {
                viewHolder.root
            }
            channelTarget.setOnClickListener {
                markLatestSeen(viewHolder.root)
                selected(infoItem)
            }
            channelTarget.setOnLongClickListener {
                held(infoItem)
                true
            }
        }

        if (itemVersion == ItemVersion.LATEST) {
            bindLatestContent(viewHolder.root)
        }
    }

    private fun bindLatestContent(root: View) {
        val latestUrl = latestStreams.firstOrNull()?.stream?.url
        val preferences = PreferenceManager.getDefaultSharedPreferences(root.context)
        val seenKey = LAST_SEEN_STREAM_PREFIX + subscriptionId
        val seenUrl = preferences.getString(seenKey, null)
        if (latestUrl != null && seenUrl == null) {
            preferences.edit { putString(seenKey, latestUrl) }
        }
        root.findViewById<View>(R.id.newUpdateDot).isVisible =
            latestUrl != null && seenUrl != null && latestUrl != seenUrl

        bindLatestVideo(root, root.findViewById(R.id.latestVideoOne), latestStreams.getOrNull(0))
        bindLatestVideo(root, root.findViewById(R.id.latestVideoTwo), latestStreams.getOrNull(1))
    }

    private fun bindLatestVideo(root: View, container: View, stream: StreamWithState?) {
        container.isVisible = stream != null
        if (stream == null) {
            container.setOnClickListener(null)
            return
        }
        container.findViewById<TextView>(R.id.itemVideoTitleView).text = stream.stream.title
        container.findViewById<TextView>(R.id.itemUploaderView).text =
            stream.stream.uploadDate?.let(Localization::relativeTime) ?: stream.stream.uploader
        CoilHelper.loadThumbnail(
            container.findViewById(R.id.itemThumbnailView),
            stream.stream.thumbnailUrl
        )
        val duration = container.findViewById<TextView>(R.id.itemDurationView)
        duration.isVisible = stream.stream.duration > 0
        if (duration.isVisible) {
            duration.text = Localization.getDurationString(stream.stream.duration)
        }
        container.findViewById<View>(R.id.itemProgressView).isVisible = false
        container.setOnClickListener {
            markLatestSeen(root)
            streamClickListener?.invoke(stream)
        }
    }

    private fun markLatestSeen(view: View) {
        val latestUrl = latestStreams.firstOrNull()?.stream?.url ?: return
        PreferenceManager.getDefaultSharedPreferences(view.context)
            .edit { putString(LAST_SEEN_STREAM_PREFIX + subscriptionId, latestUrl) }
        view.findViewById<View>(R.id.newUpdateDot)?.isVisible = false
    }

    private fun getDetailLine(context: Context): String {
        var details = if (infoItem.subscriberCount >= 0) {
            Localization.shortSubscriberCount(context, infoItem.subscriberCount)
        } else {
            context.getString(R.string.subscribers_count_not_available)
        }

        if (itemVersion == ItemVersion.NORMAL && infoItem.streamCount >= 0) {
            val formattedVideoAmount = Localization.localizeStreamCount(context, infoItem.streamCount)
            details = Localization.concatenateStrings(details, formattedVideoAmount)
        }
        return details
    }

    override fun getSpanSize(spanCount: Int, position: Int): Int {
        return if (itemVersion == ItemVersion.GRID) 1 else spanCount
    }

    private companion object {
        const val LAST_SEEN_STREAM_PREFIX = "subscription_latest_seen_"
    }
}
