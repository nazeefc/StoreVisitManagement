package com.example.storevisitmanagement.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.storevisitmanagement.R
import com.example.storevisitmanagement.databinding.FragmentLoginBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSignIn.setOnClickListener {
            val employeeId = binding.etEmployeeId.text?.toString()?.trim() ?: ""
            // CRITICAL: Preserve exact password characters entered by user (do not trim passwords)
            val password = binding.etPassword.text?.toString() ?: ""

            var isValid = true

            if (employeeId.isEmpty()) {
                binding.tilEmployeeId.error = getString(R.string.error_employee_id_empty)
                isValid = false
            } else {
                binding.tilEmployeeId.error = null
            }

            if (password.isEmpty()) {
                binding.tilPassword.error = getString(R.string.error_password_empty)
                isValid = false
            } else {
                binding.tilPassword.error = null
            }

            if (isValid) {
                binding.tilEmployeeId.error = null
                binding.tilPassword.error = null
                viewModel.login(employeeId, password)
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Idle -> {
                            setLoading(false)
                        }
                        is AuthUiState.Loading -> {
                            setLoading(true)
                        }
                        is AuthUiState.Success -> {
                            setLoading(false)
                            viewModel.resetState()
                            findNavController().navigate(R.id.action_loginFragment_to_storeListFragment)
                        }
                        is AuthUiState.Error -> {
                            setLoading(false)
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG)
                                .setAnchorView(binding.btnSignIn)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.btnSignIn.isEnabled = !isLoading
        binding.etEmployeeId.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        binding.btnSignIn.text = if (isLoading) "Signing in..." else getString(R.string.sign_in_button)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
