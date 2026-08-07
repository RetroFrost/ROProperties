package dev.retrofrost.roproperties.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.retrofrost.roproperties.data.PropertyRepository
import dev.retrofrost.roproperties.knowledge.PropertyKnowledgeEngine
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.PropertyCategory
import dev.retrofrost.roproperties.model.PropertyCategoryClassifier
import dev.retrofrost.roproperties.model.PropertyUiItem
import dev.retrofrost.roproperties.model.RootCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val loading: Boolean = true,
    val applying: Boolean = false,
    val properties: List<PropertyUiItem> = emptyList(),
    val query: String = "",
    val category: PropertyCategory = PropertyCategory.ALL,
    val confidenceFilter: KnowledgeConfidence? = null,
    val capabilities: RootCapabilities = RootCapabilities(),
    val message: String? = null,
) {
    val filteredProperties: List<PropertyUiItem>
        get() = properties.filter { item ->
            val queryMatches = query.isBlank() ||
                item.property.name.contains(query, ignoreCase = true) ||
                item.property.value.contains(query, ignoreCase = true) ||
                item.explanation.propertyMeaning.contains(query, ignoreCase = true) ||
                item.explanation.valueMeaning.contains(query, ignoreCase = true)
            val confidenceMatches = confidenceFilter == null || item.explanation.confidence == confidenceFilter
            val categoryMatches = PropertyCategoryClassifier.matches(category, item)
            queryMatches && confidenceMatches && categoryMatches
        }

    fun countFor(category: PropertyCategory): Int =
        properties.count { PropertyCategoryClassifier.matches(category, it) }
}

class MainViewModel(
    private val repository: PropertyRepository = PropertyRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val (properties, capabilities) = coroutineScope {
                val propertiesDeferred = async { repository.loadProperties() }
                val capabilitiesDeferred = async { repository.detectCapabilities() }
                propertiesDeferred.await() to capabilitiesDeferred.await()
            }
            _state.update {
                it.copy(
                    loading = false,
                    properties = properties.map { property ->
                        PropertyUiItem(property, PropertyKnowledgeEngine.explain(property))
                    },
                    capabilities = capabilities,
                )
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setCategory(category: PropertyCategory) =
        _state.update { it.copy(category = category, query = "") }

    fun setConfidenceFilter(filter: KnowledgeConfidence?) =
        _state.update { it.copy(confidenceFilter = filter) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun apply(item: PropertyUiItem, value: String, mode: EditMode) {
        viewModelScope.launch {
            _state.update { it.copy(applying = true, message = null) }
            val result = repository.apply(item.property.name, value, mode)
            _state.update { it.copy(applying = false, message = result.message) }
            if (result.success || result.runtimeApplied) refresh()
        }
    }
}
