package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.databinding.ItemNewsBinding
import com.dlang.homewx.news.NewsItem
import com.squareup.picasso.Picasso

class NewsAdapter(private val onItemClick: (NewsItem) -> Unit) : RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

    private var items: List<NewsItem> = emptyList()

    fun submit(newItems: List<NewsItem>) {
        // observeState() calls this on every AppState.uiState tick (sensors, lux, weather...),
        // not just when news actually changes - skip the rebind or thumbnails flash on every tick.
        if (newItems == items) return
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemNewsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], onItemClick)

    override fun onViewRecycled(holder: ViewHolder) = holder.unbind()

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemNewsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NewsItem, onItemClick: (NewsItem) -> Unit) {
            binding.newsTitleText.text = item.title
            binding.newsDescriptionText.text = item.description
            if (item.imageUrl != null) {
                // Some RSS thumbnails come back at full photo resolution; resize during decode
                // so Picasso never hands the canvas a bitmap too large to draw (crash otherwise).
                val density = binding.newsThumbnail.resources.displayMetrics.density
                Picasso.get().load(item.imageUrl)
                    .resize((THUMBNAIL_WIDTH_DP * density).toInt(), (THUMBNAIL_HEIGHT_DP * density).toInt())
                    .centerCrop()
                    .into(binding.newsThumbnail)
            } else {
                Picasso.get().cancelRequest(binding.newsThumbnail)
                binding.newsThumbnail.setImageDrawable(null)
            }
            binding.root.setOnClickListener { onItemClick(item) }
        }

        fun unbind() {
            Picasso.get().cancelRequest(binding.newsThumbnail)
        }

        companion object {
            // Must match item_news.xml newsThumbnail layout_width/layout_height.
            private const val THUMBNAIL_WIDTH_DP = 110
            private const val THUMBNAIL_HEIGHT_DP = 74
        }
    }
}
