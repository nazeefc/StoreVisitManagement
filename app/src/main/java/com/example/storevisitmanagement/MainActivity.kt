package com.example.storevisitmanagement

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.storevisitmanagement.data.sync.SyncManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(applicationContext)
        SyncManager.init(applicationContext)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        observeSessionState()
    }

    private fun observeSessionState() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionManager.isSessionValid.collectLatest { isValid ->
                    if (!isValid && navController != null) {
                        val currentDestId = navController.currentDestination?.id
                        if (currentDestId != null && currentDestId != R.id.loginFragment) {
                            try {
                                navController.navigate(
                                    R.id.loginFragment,
                                    null,
                                    androidx.navigation.NavOptions.Builder()
                                        .setPopUpTo(R.id.nav_graph, true)
                                        .build()
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }
}
