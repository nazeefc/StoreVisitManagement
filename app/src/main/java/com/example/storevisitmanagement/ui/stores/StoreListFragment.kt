package com.example.storevisitmanagement.ui.stores

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.storevisitmanagement.R
import com.example.storevisitmanagement.databinding.FragmentStoreListBinding
import kotlinx.coroutines.launch

class StoreListFragment : Fragment() {
    private var _binding: FragmentStoreListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StoreListViewModel by viewModels()
    private lateinit var storeAdapter: StoreListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoreListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        storeAdapter = StoreListAdapter(
            onStoreClick = { store ->
                val bundle = Bundle().apply {
                    putString("storeId", store.id)
                    putString("storeName", store.name)
                    putString("storeCode", store.code)
                }
                findNavController().navigate(R.id.action_storeListFragment_to_taskListFragment, bundle)
            }
        )
        binding.rvStores.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = storeAdapter
        }
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
        }

        binding.btnRetry.setOnClickListener {
            binding.etSearch.setText("")
            viewModel.fetchStores("")
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.fetchStores(s?.toString() ?: "")
            }
        })
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

    private fun updateUi(state: StoreListUiState) {
        binding.progressBar.visibility = View.GONE
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.rvStores.visibility = View.GONE

        when (state) {
            is StoreListUiState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
            }
            is StoreListUiState.Success -> {
                binding.rvStores.visibility = View.VISIBLE
                storeAdapter.submitList(state.stores)
                binding.tvGreeting.text = "Welcome, ${state.employeeName}"
                binding.tvStoreCount.text = "${state.totalCount} Assigned Stores"
            }
            is StoreListUiState.Empty -> {
                binding.layoutEmpty.visibility = View.VISIBLE
                storeAdapter.submitList(emptyList())
            }
            is StoreListUiState.Error -> {
                binding.layoutError.visibility = View.VISIBLE
                binding.tvErrorMessage.text = state.message
            }
            is StoreListUiState.LoggedOut -> {
                navigateToLogin()
            }
        }
    }

    private fun navigateToLogin() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.storeListFragment) {
            navController.navigate(R.id.action_storeListFragment_to_loginFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
