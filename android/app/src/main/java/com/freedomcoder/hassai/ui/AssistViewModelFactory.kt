package com.freedomcoder.hassai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.freedomcoder.hassai.data.AssistantRepository

class AssistViewModelFactory(
    private val repository: AssistantRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AssistViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}
