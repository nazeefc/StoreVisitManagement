package com.example.storevisitmanagement.ui.stores

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagement.data.remote.model.StoreDto
import com.example.storevisitmanagement.databinding.ItemStoreBinding

class StoreListAdapter : ListAdapter<StoreDto, StoreListAdapter.StoreViewHolder>(StoreDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreViewHolder {
        val binding = ItemStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StoreViewHolder(private val binding: ItemStoreBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(store: StoreDto) {
            binding.tvStoreName.text = store.name
            binding.tvStoreCode.text = store.code
            binding.tvStoreAddress.text = store.address
        }
    }

    class StoreDiffCallback : DiffUtil.ItemCallback<StoreDto>() {
        override fun areItemsTheSame(oldItem: StoreDto, newItem: StoreDto): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StoreDto, newItem: StoreDto): Boolean {
            return oldItem == newItem
        }
    }
}
