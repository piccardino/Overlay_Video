package com.example.overlayvideoapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.overlayvideoapp.data.FirebaseMatchRepository
import com.example.overlayvideoapp.data.MatchData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CameraViewModel : ViewModel() {
    private val repository = FirebaseMatchRepository()

    val matchData: StateFlow<MatchData?> = repository.matchData
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val connectionState: StateFlow<Boolean> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        // Initialization without observing.
        // Wait for MainActivity to provide UID.
    }

    fun startListening(uid: String, matchKey: String, source: String = "local") {
        repository.startObserving(uid, matchKey, source)
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopObserving()
    }
}
