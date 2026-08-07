package com.cubical.roproperties.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cubical.roproperties.data.AndroidProperty
import com.cubical.roproperties.data.ApplyMode
import com.cubical.roproperties.data.PropertyRepository
import com.cubical.roproperties.data.RootCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PropertyUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val properties: List<AndroidProperty> = emptyList(),
    val query: String = "",
    val category: String = "All",
    val root: RootCapability = RootCapability(),
    val backupCount: Int = 0,
    val latestBackupValue: String? = null,
    val selected: AndroidProperty? = null,
    val showAbout: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PropertyRepository(application)
    private val mutableState = MutableStateFlow(PropertyUiState())
    val state: StateFlow<PropertyUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatching {
                val root = repository.probeRoot()
                val properties = repository.loadProperties(root.rootGranted)
                Triple(root, properties, repository.backupCount())
            }.onSuccess { (root, properties, backups) ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        root = root,
                        properties = properties,
                        backupCount = backups,
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Unable to load properties.",
                    )
                }
            }
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun setCategory(category: String) = mutableState.update { it.copy(category = category) }

    fun openEditor(property: AndroidProperty) {
        mutableState.update { it.copy(selected = property, latestBackupValue = null) }
        viewModelScope.launch {
            val backupValue = repository.latestBackupValue(property.name)
            mutableState.update {
                if (it.selected?.name == property.name) it.copy(latestBackupValue = backupValue) else it
            }
        }
    }

    fun closeEditor() = mutableState.update { it.copy(selected = null, latestBackupValue = null) }

    fun showAbout(show: Boolean) = mutableState.update { it.copy(showAbout = show) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun save(property: AndroidProperty, value: String, mode: ApplyMode) {
        if (mutableState.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true) }
            runCatching {
                repository.createBackup(mutableState.value.properties)
                when (mode) {
                    ApplyMode.RUNTIME -> repository.applyRuntime(property.name, value)
                    ApplyMode.PERSISTENT -> repository.applyPersistent(property.name, value)
                }
            }.onSuccess { result ->
                mutableState.update {
                    it.copy(
                        saving = false,
                        selected = if (result.success) null else it.selected,
                        message = result.message,
                    )
                }
                if (result.success) reloadAfterEdit(result.message)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(saving = false, message = error.message ?: "Edit failed.")
                }
            }
        }
    }

    fun removePersistent(property: AndroidProperty) {
        if (mutableState.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true) }
            val result = runCatching { repository.removePersistent(property.name) }
                .getOrElse { com.cubical.roproperties.data.EditResult(false, it.message ?: "Removal failed.") }
            mutableState.update {
                it.copy(
                    saving = false,
                    selected = if (result.success) null else it.selected,
                    message = result.message,
                )
            }
            if (result.success) reloadAfterEdit(result.message)
        }
    }

    private fun reloadAfterEdit(message: String) {
        viewModelScope.launch {
            val currentRoot = mutableState.value.root
            runCatching {
                repository.loadProperties(currentRoot.rootGranted) to repository.backupCount()
            }.onSuccess { (properties, backups) ->
                mutableState.update {
                    it.copy(properties = properties, backupCount = backups, message = message)
                }
            }
        }
    }
}
