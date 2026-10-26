package com.qwantarc.messenger

import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the app is on screen. The activity keeps it current; services read it. */
object AppVisibility {
    val onScreen = MutableStateFlow(false)
}
