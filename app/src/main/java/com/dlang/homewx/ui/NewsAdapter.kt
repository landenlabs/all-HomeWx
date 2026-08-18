package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.databinding.ItemNewsBinding
import com.dlang.homewx.news.NewsItem

class NewsAdapter : RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

    private var items: List<NewsItem> = emptyList()

    fun submit(newItems: List<NewsItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemNewsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemNewsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NewsItem) {
            binding.newsTitleText.text = item.title
            binding.newsDescriptionText.text = item.description
            if (item.imageUrl != null) {
                NewsImageLoader.load(item.imageUrl, binding.newsThumbnail)
            } else {
                binding.newsThumbnail.tag = null
                binding.newsThumbnail.setImageDrawable(null)
            }
        }
    }
}
