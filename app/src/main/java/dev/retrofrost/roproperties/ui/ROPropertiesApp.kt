package dev.retrofrost.roproperties.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.PropertyUiItem
import dev.retrofrost.roproperties.model.RiskLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ROPropertiesApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<PropertyUiItem?>(null) }
    var editing by remember { mutableStateOf<PropertyUiItem?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ROProperties", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.properties.size} ro.* properties",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Text("Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            RootStatus(state)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search properties or meanings") },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            ConfidenceFilters(
                selected = state.confidenceFilter,
                onSelected = viewModel::setConfidenceFilter,
            )
            Spacer(Modifier.height(8.dp))

            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Reading Android properties…")
                }
            } else if (state.filteredProperties.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No matching ro.* properties")
                    Text(
                        "Try another search or filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.filteredProperties, key = { it.property.name }) { item ->
                        PropertyCard(item) { selected = item }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    selected?.let { item ->
        PropertyDetailsSheet(
            item = item,
            canEdit = state.capabilities.rootAvailable,
            runtimeAvailable = state.capabilities.resetPropAvailable,
            persistentAvailable = state.capabilities.modulePersistenceAvailable,
            onDismiss = { selected = null },
            onEdit = { editing = item },
        )
    }

    editing?.let { item ->
        EditPropertyDialog(
            item = item,
            runtimeAvailable = state.capabilities.resetPropAvailable,
            persistentAvailable = state.capabilities.modulePersistenceAvailable,
            applying = state.applying,
            onDismiss = { if (!state.applying) editing = null },
            onApply = { value, mode ->
                viewModel.apply(item, value, mode)
                editing = null
                selected = null
            },
        )
    }
}

@Composable
private fun RootStatus(state: MainUiState) {
    val caps = state.capabilities
    val label = when {
        state.loading -> "Checking root…"
        !caps.rootAvailable -> "Read-only · no root"
        caps.resetPropAvailable && caps.modulePersistenceAvailable -> "${caps.framework} · runtime + persistent"
        caps.resetPropAvailable -> "${caps.framework} · runtime only"
        caps.modulePersistenceAvailable -> "${caps.framework} · persistent only"
        else -> "${caps.framework} · editing unsupported"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun ConfidenceFilters(
    selected: KnowledgeConfidence?,
    onSelected: (KnowledgeConfidence?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text("All") },
        )
        FilterChip(
            selected = selected == KnowledgeConfidence.DOCUMENTED,
            onClick = { onSelected(KnowledgeConfidence.DOCUMENTED) },
            label = { Text("Documented") },
        )
        FilterChip(
            selected = selected == KnowledgeConfidence.INFERRED,
            onClick = { onSelected(KnowledgeConfidence.INFERRED) },
            label = { Text("Inferred") },
        )
    }
}

@Composable
private fun PropertyCard(item: PropertyUiItem, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.property.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.explanation.confidence.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.property.value.ifEmpty { "(empty)" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                item.explanation.propertyMeaning,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyDetailsSheet(
    item: PropertyUiItem,
    canEdit: Boolean,
    runtimeAvailable: Boolean,
    persistentAvailable: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(item.property.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(
                item.property.value.ifEmpty { "(empty)" },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            DetailSection("What this property means", item.explanation.propertyMeaning)
            DetailSection("What this value means", item.explanation.valueMeaning)

            if (item.explanation.knownValues.isNotEmpty()) {
                Text("Known values", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                item.explanation.knownValues.forEach { (value, meaning) ->
                    Text("$value — $meaning", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            MetadataRow("Origin", item.explanation.origin)
            MetadataRow("Confidence", item.explanation.confidence.label)
            MetadataRow("Risk", item.explanation.risk.label)
            MetadataRow("Likely consumers", item.explanation.consumers)
            MetadataRow("Editing", item.explanation.editBehaviour)
            MetadataRow("Reboot behaviour", item.explanation.rebootRequirement)

            Spacer(Modifier.height(18.dp))
            if (!canEdit) {
                Text(
                    "Root is not available, so this device is currently read-only.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!runtimeAvailable && !persistentAvailable) {
                Text(
                    "Root works, but neither resetprop nor a module persistence directory is available.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit property")
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EditPropertyDialog(
    item: PropertyUiItem,
    runtimeAvailable: Boolean,
    persistentAvailable: Boolean,
    applying: Boolean,
    onDismiss: () -> Unit,
    onApply: (String, EditMode) -> Unit,
) {
    var value by rememberSaveable(item.property.name) { mutableStateOf(item.property.value) }
    var mode by rememberSaveable(item.property.name) {
        mutableStateOf(
            when {
                runtimeAvailable && persistentAvailable -> EditMode.BOTH
                runtimeAvailable -> EditMode.RUNTIME
                else -> EditMode.PERSISTENT
            }
        )
    }
    var acknowledged by rememberSaveable(item.property.name) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${item.property.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Risk: ${item.explanation.risk.label}. A successful property override can still break apps, framework services or boot-sensitive behaviour.",
                    color = if (item.explanation.risk >= RiskLevel.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("New value") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Apply mode", fontWeight = FontWeight.SemiBold)
                EditMode.entries.forEach { candidate ->
                    val supported = when (candidate) {
                        EditMode.RUNTIME -> runtimeAvailable
                        EditMode.PERSISTENT -> persistentAvailable
                        EditMode.BOTH -> runtimeAvailable && persistentAvailable
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == candidate,
                            onClick = { if (supported) mode = candidate },
                            enabled = supported,
                        )
                        Text(candidate.label, color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I understand that changing this ro.* value may destabilise the device.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(value, mode) },
                enabled = acknowledged && !applying,
            ) {
                if (applying) CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                else Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying) { Text("Cancel") }
        },
    )
}
