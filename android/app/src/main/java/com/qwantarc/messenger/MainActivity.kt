package com.qwantarc.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.qwantarc.messenger.ui.MessengerTheme
import com.qwantarc.messenger.ui.MessengerUi

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MessengerTheme {
                MessengerUi(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.syncNow()
    }
}
