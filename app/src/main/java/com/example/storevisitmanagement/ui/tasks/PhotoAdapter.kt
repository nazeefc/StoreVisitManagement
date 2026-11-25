package com.example.storevisitmanagement.ui.tasks

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.databinding.ItemPhotoThumbBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoAdapter(
    private val viewLifecycleOwner: LifecycleOwner,
    private val onPhotoClick: (PhotoEntity) -> Unit,
    private val onDeletePhoto: (PhotoEntity) -> Unit
) : ListAdapter<PhotoEntity, PhotoAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding, viewLifecycleOwner, onPhotoClick, onDeletePhoto)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    class PhotoViewHolder(
        private val binding: ItemPhotoThumbBinding,
        private val viewLifecycleOwner: LifecycleOwner,
        private val onPhotoClick: (PhotoEntity) -> Unit,
        private val onDeletePhoto: (PhotoEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
            binding.ivPhotoThumb.setImageDrawable(null)
        }

        fun bind(photo: PhotoEntity) {
            binding.ivPhotoThumb.setImageDrawable(null)
            loadJob?.cancel()

            loadJob = viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val file = File(photo.localFilePath)
                        if (file.exists()) {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 4
                            }
                            BitmapFactory.decodeFile(file.absolutePath, options)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    binding.ivPhotoThumb.setImageBitmap(bitmap)
                } else {
                    binding.ivPhotoThumb.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }

            binding.root.setOnClickListener {
                onPhotoClick(photo)
            }

            binding.btnDeletePhoto.setOnClickListener {
                onDeletePhoto(photo)
            }
        }
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<PhotoEntity>() {
        override fun areItemsTheSame(oldItem: PhotoEntity, newItem: PhotoEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PhotoEntity, newItem: PhotoEntity): Boolean {
            return oldItem == newItem
        }
    }
}
