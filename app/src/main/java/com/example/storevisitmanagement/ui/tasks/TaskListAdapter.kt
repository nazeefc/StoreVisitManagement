package com.example.storevisitmanagement.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import com.example.storevisitmanagement.databinding.ItemTaskBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class TaskListAdapter(
    private val viewLifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val onToggleComplete: (TaskEntity, Boolean) -> Unit,
    private val onEdit: (TaskEntity) -> Unit,
    private val onDelete: (TaskEntity) -> Unit,
    private val onAddPhoto: (TaskEntity) -> Unit,
    private val onPhotoClick: (PhotoEntity) -> Unit,
    private val onDeletePhoto: (PhotoEntity) -> Unit,
    private val getPhotosFlow: (String) -> Flow<List<PhotoEntity>>
) : ListAdapter<TaskEntity, TaskListAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskViewHolder(binding, viewLifecycleOwner, onToggleComplete, onEdit, onDelete, onAddPhoto, onPhotoClick, onDeletePhoto, getPhotosFlow)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: TaskViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    class TaskViewHolder(
        private val binding: ItemTaskBinding,
        private val viewLifecycleOwner: androidx.lifecycle.LifecycleOwner,
        private val onToggleComplete: (TaskEntity, Boolean) -> Unit,
        private val onEdit: (TaskEntity) -> Unit,
        private val onDelete: (TaskEntity) -> Unit,
        private val onAddPhoto: (TaskEntity) -> Unit,
        private val onPhotoClick: (PhotoEntity) -> Unit,
        private val onDeletePhoto: (PhotoEntity) -> Unit,
        private val getPhotosFlow: (String) -> Flow<List<PhotoEntity>>
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context = binding.root.context
        private val photoAdapter = PhotoAdapter(viewLifecycleOwner, onPhotoClick, onDeletePhoto)
        private var currentJob: kotlinx.coroutines.Job? = null

        init {
            binding.rvTaskPhotos.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = photoAdapter
            }
        }

        fun cancelLoad() {
            currentJob?.cancel()
            currentJob = null
            photoAdapter.submitList(emptyList())
        }

        fun bind(task: TaskEntity) {
            binding.tvTaskTitle.text = task.title
            binding.tvTaskCategory.text = task.category
            binding.tvTaskDescription.text = task.description
            binding.tvTaskDescription.visibility = if (task.description.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

            binding.cbTaskComplete.setOnCheckedChangeListener(null)
            binding.cbTaskComplete.isChecked = task.isCompleted

            binding.cbTaskComplete.setOnCheckedChangeListener { _, isChecked ->
                onToggleComplete(task, isChecked)
            }

            binding.btnAddPhoto.setOnClickListener {
                onAddPhoto(task)
            }

            binding.btnEditTask.setOnClickListener {
                onEdit(task)
            }

            binding.btnDeleteTask.setOnClickListener {
                onDelete(task)
            }

            // Cancel previous collection job if view holder is recycled or rebound
            currentJob?.cancel()
            currentJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    getPhotosFlow(task.id).collect { photos ->
                        photoAdapter.submitList(photos)
                    }
                }
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<TaskEntity>() {
        override fun areItemsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean {
            return oldItem == newItem
        }
    }
}
