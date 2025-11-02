package com.abandonsearch.hazardgrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.abandonsearch.hazardgrid.ui.HazardGridApp
import com.abandonsearch.hazardgrid.ui.theme.HazardGridTheme
import world.mappable.mapkit.MapKitFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Replace with your API key. You can get one at https://mappable.world/account/
        MapKitFactory.setApiKey("YOUR_API_KEY")
        MapKitFactory.initialize(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            HazardGridTheme {
                HazardGridApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }
}
