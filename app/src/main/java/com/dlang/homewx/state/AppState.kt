package com.dlang.homewx.state

import com.dlang.homewx.model.UiState
import kotlinx.coroutines.flow.MutableStateFlow

object AppState {
    val uiState = MutableStateFlow(UiState())
}
