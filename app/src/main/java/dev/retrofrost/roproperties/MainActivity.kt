package dev.retrofrost.roproperties

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.retrofrost.roproperties.ui.ROPropertiesApp
import dev.retrofrost.roproperties.ui.theme.ROPropertiesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ROPropertiesTheme {
                ROPropertiesApp()
            }
        }
    }
}
