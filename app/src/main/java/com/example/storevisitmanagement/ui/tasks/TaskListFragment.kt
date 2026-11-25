package com.example.storevisitmanagement.ui.tasks

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.storevisitmanagement.R
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import com.example.storevisitmanagement.databinding.DialogPhotoPreviewBinding
import com.example.storevisitmanagement.databinding.DialogTaskEditBinding
import com.example.storevisitmanagement.databinding.FragmentTaskListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.example.storevisitmanagement.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TaskListFragment : Fragment() {
    private var _binding: FragmentTaskListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TaskViewModel by viewModels()
    private lateinit var taskAdapter: TaskListAdapter

    private var pendingCaptureFile: File? = null
    private var pendingCaptureUri: Uri? = null
    private var pendingTaskId: String? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCaptureFile
        val taskId = pendingTaskId
        if (success && file != null && taskId != null && file.exists()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val compressedPath = ImageUtils.compressAndSaveImage(requireContext(), file)
                if (compressedPath != null) {
                    try {
                        viewModel.saveCapturedPhoto(taskId, compressedPath)
                        Snackbar.make(binding.root, "Photo attached successfully", Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.fabAddTask)
                            .show()
                    } catch (e: Exception) {
                        try { File(compressedPath).delete() } catch (_: Exception) {}
                        Snackbar.make(binding.root, "Failed to save photo reference: ${e.message}", Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.fabAddTask)
                            .show()
                    }
                } else {
                    Snackbar.make(binding.root, "Failed to process captured image", Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.fabAddTask)
                        .show()
                }
                pendingCaptureFile = null
                pendingCaptureUri = null
                pendingTaskId = null
            }
        } else {
            if (file != null && file.exists()) {
                try { file.delete() } catch (_: Exception) {}
            }
            pendingCaptureFile = null
            pendingCaptureUri = null
            pendingTaskId = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null) {
            val filePath = savedInstanceState.getString("pending_capture_filepath")
            if (filePath != null) {
                pendingCaptureFile = File(filePath)
                pendingCaptureUri = ImageUtils.createTempImageUri(requireContext(), pendingCaptureFile!!)
            }
            pendingTaskId = savedInstanceState.getString("pending_task_id")
        }

        val storeId = arguments?.getString("storeId") ?: ""
        val storeName = arguments?.getString("storeName") ?: ""
        val storeCode = arguments?.getString("storeCode") ?: ""

        binding.tvStoreHeaderName.text = storeName
        binding.tvStoreHeaderCode.text = storeCode

        viewModel.initStore(storeId)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCaptureFile?.let { outState.putString("pending_capture_filepath", it.absolutePath) }
        outState.putString("pending_task_id", pendingTaskId)
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskListAdapter(
            viewLifecycleOwner = viewLifecycleOwner,
            onToggleComplete = { task, isCompleted ->
                viewModel.toggleTaskCompletion(task, isCompleted)
            },
            onEdit = { task ->
                showEditTaskDialog(task)
            },
            onDelete = { task ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Task")
                    .setMessage("Are you sure you want to delete this task and its associated photo evidence?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteTask(task) { success, errorMsg ->
                            if (_binding == null) return@deleteTask
                            if (success) {
                                Snackbar.make(binding.root, "Task deleted successfully", Snackbar.LENGTH_SHORT)
                                    .setAnchorView(binding.fabAddTask)
                                    .show()
                            } else {
                                Snackbar.make(binding.root, errorMsg ?: "Failed to delete task", Snackbar.LENGTH_LONG)
                                    .setAnchorView(binding.fabAddTask)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onAddPhoto = { task ->
                launchCameraForTask(task)
            },
            onPhotoClick = { photo ->
                showPhotoPreview(photo)
            },
            onDeletePhoto = { photo ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Photo")
                    .setMessage("Are you sure you want to delete this photo evidence?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                viewModel.deletePhoto(photo.id)
                                Snackbar.make(binding.root, "Photo removed", Snackbar.LENGTH_SHORT)
                                    .setAnchorView(binding.fabAddTask)
                                    .show()
                            } catch (e: Exception) {
                                Snackbar.make(binding.root, "Failed to remove photo: ${e.message}", Snackbar.LENGTH_LONG)
                                    .setAnchorView(binding.fabAddTask)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            getPhotosFlow = { taskId ->
                viewModel.getPhotosFlow(taskId)
            }
        )

        binding.rvTasks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
        }
    }

    private fun launchCameraForTask(task: TaskEntity) {
        try {
            val file = ImageUtils.createTempImageFile(requireContext())
            val uri = ImageUtils.createTempImageUri(requireContext(), file)
            pendingCaptureFile = file
            pendingCaptureUri = uri
            pendingTaskId = task.id
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Unable to launch camera: ${e.message}", Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAddTask)
                .show()
        }
    }

    private fun showPhotoPreview(photo: PhotoEntity) {
        val file = File(photo.localFilePath)
        if (!file.exists()) {
            Snackbar.make(binding.root, "Photo file is missing or deleted from storage.", Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAddTask)
                .show()
            return
        }

        val dialogBinding = DialogPhotoPreviewBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogBinding.root)
            .create()

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                dialogBinding.ivFullPhoto.setImageBitmap(bitmap)
            }
        }

        dialogBinding.btnClosePreview.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.fabAddTask.setOnClickListener {
            showEditTaskDialog(null)
        }

        binding.chipAll.setOnClickListener { viewModel.setFilter(TaskFilter.ALL) }
        binding.chipPending.setOnClickListener { viewModel.setFilter(TaskFilter.PENDING) }
        binding.chipCompleted.setOnClickListener { viewModel.setFilter(TaskFilter.COMPLETED) }
    }

    private fun showEditTaskDialog(existingTask: TaskEntity?) {
        val dialogBinding = DialogTaskEditBinding.inflate(layoutInflater)
        val isEditing = existingTask != null

        dialogBinding.tvDialogTitle.text = if (isEditing) "Edit Task" else "New Task"
        if (isEditing) {
            dialogBinding.etTaskTitle.setText(existingTask.title)
            dialogBinding.etTaskDescription.setText(existingTask.description)
            when (existingTask.category) {
                "MERCHANDISING" -> dialogBinding.rbMerchandising.isChecked = true
                "AUDIT" -> dialogBinding.rbAudit.isChecked = true
                else -> dialogBinding.rbInventory.isChecked = true
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelTask.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveTask.setOnClickListener {
            val title = dialogBinding.etTaskTitle.text?.toString()?.trim() ?: ""
            val description = dialogBinding.etTaskDescription.text?.toString()?.trim() ?: ""

            if (title.isEmpty()) {
                dialogBinding.tilTaskTitle.error = "Task title is required"
                return@setOnClickListener
            }
            if (title.length > 100) {
                dialogBinding.tilTaskTitle.error = "Title cannot exceed 100 characters"
                return@setOnClickListener
            }
            if (description.length > 500) {
                dialogBinding.tilTaskDescription.error = "Description cannot exceed 500 characters"
                return@setOnClickListener
            }

            dialogBinding.tilTaskTitle.error = null
            dialogBinding.tilTaskDescription.error = null

            val category = when (dialogBinding.rgCategory.checkedRadioButtonId) {
                R.id.rbMerchandising -> "MERCHANDISING"
                R.id.rbAudit -> "AUDIT"
                else -> "INVENTORY"
            }

            dialogBinding.btnSaveTask.isEnabled = false

            if (isEditing) {
                viewModel.updateTask(existingTask, title, description, category) { success, errorMsg ->
                    if (_binding == null) return@updateTask
                    dialogBinding.btnSaveTask.isEnabled = true
                    if (success) {
                        Snackbar.make(binding.root, "Task updated successfully", Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.fabAddTask)
                            .show()
                        dialog.dismiss()
                    } else {
                        Snackbar.make(binding.root, errorMsg ?: "Failed to update task", Snackbar.LENGTH_LONG).show()
                    }
                }
            } else {
                viewModel.saveTask(title, description, category) { success, errorMsg ->
                    if (_binding == null) return@saveTask
                    dialogBinding.btnSaveTask.isEnabled = true
                    if (success) {
                        Snackbar.make(binding.root, "Task created successfully", Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.fabAddTask)
                            .show()
                        dialog.dismiss()
                    } else {
                        Snackbar.make(binding.root, errorMsg ?: "Failed to create task", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    private fun updateUi(state: TaskListUiState) {
        binding.layoutEmpty.visibility = View.GONE
        binding.rvTasks.visibility = View.GONE

        when (state) {
            is TaskListUiState.Loading -> {
                // Keep default
            }
            is TaskListUiState.Success -> {
                binding.rvTasks.visibility = View.VISIBLE
                taskAdapter.submitList(state.tasks)
            }
            is TaskListUiState.Empty -> {
                binding.layoutEmpty.visibility = View.VISIBLE
                taskAdapter.submitList(emptyList())
            }
            is TaskListUiState.Error -> {
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabAddTask)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
