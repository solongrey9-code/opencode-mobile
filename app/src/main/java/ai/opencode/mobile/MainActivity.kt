package ai.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ai.opencode.mobile.ui.AppViewModel
import ai.opencode.mobile.ui.OpenCodeApp
import ai.opencode.mobile.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenCodeTheme {
                OpenCodeApp(viewModel)
            }
        }
    }
}
