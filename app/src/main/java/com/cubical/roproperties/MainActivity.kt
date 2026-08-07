package com.cubical.roproperties

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cubical.roproperties.ui.PropertyEditorApp
import com.cubical.roproperties.ui.theme.ROPropertiesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ROPropertiesTheme {
                PropertyEditorApp(viewModel())
            }
        }
    }
}
