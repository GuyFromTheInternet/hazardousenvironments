package com.abandonsearch.hazardgrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.abandonsearch.hazardgrid.ui.HazardGridApp
import com.abandonsearch.hazardgrid.ui.theme.HazardGridTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            HazardGridTheme {
                HazardGridApp()
            }
        }
    }
}
