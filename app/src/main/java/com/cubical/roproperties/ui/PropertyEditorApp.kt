package com.cubical.roproperties.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cubical.roproperties.data.AndroidProperty
import com.cubical.roproperties.data.ApplyMode
import com.cubical.roproperties.data.PropertyRisk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyEditorApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RO Properties", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Read, understand, and override",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.showAbout(true) }) { Text("About") }
                },
            )
        },
    ) { scaffoldPadding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(scaffoldPadding))
            state.error != null -> ErrorScreen(
                error = state.error,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(scaffoldPadding),
            )
            else -> PropertyList(
                state = state,
                onQuery = viewModel::setQuery,
                onCategory = viewModel::setCategory,
                onRefresh = viewModel::refresh,
                onEdit = viewModel::openEditor,
                modifier = Modifier.padding(scaffoldPadding),
            )
        }
    }

    state.selected?.let { property ->
        EditPropertyDialog(
            property = property,
            latestBackupValue = state.latestBackupValue,
            rootReady = state.root.rootGranted && state.root.resetPropAvailable,
            saving = state.saving,
            onDismiss = viewModel::closeEditor,
            onSave = { value, mode -> viewModel.save(property, value, mode) },
            onRemovePersistent = { viewModel.removePersistent(property) },
        )
    }

    if (state.showAbout) {
        AboutDialog(onDismiss = { viewModel.showAbout(false) })
    }
}

@Composable
private fun PropertyList(
    state: PropertyUiState,
    onQuery: (String) -> Unit,
    onCategory: (String) -> Unit,
    onRefresh: () -> Unit,
    onEdit: (AndroidProperty) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = remember(state.properties) {
        listOf("All") + state.properties.map { it.category }.distinct().sorted()
    }
    val visible = remember(state.properties, state.query, state.category) {
        val needle = state.query.trim()
        state.properties.filter { property ->
            (state.category == "All" || property.category == state.category) &&
                (needle.isBlank() || listOf(
                    property.name,
                    property.value,
                    property.description,
                ).any { it.contains(needle, ignoreCase = true) })
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RootStatusCard(
                rootGranted = state.root.rootGranted,
                resetPropAvailable = state.root.resetPropAvailable,
                implementation = state.root.implementation,
                detail = state.root.detail,
                backupCount = state.backupCount,
                onRefresh = onRefresh,
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search properties") },
                placeholder = { Text("Name, value, or description") },
                trailingIcon = if (state.query.isNotBlank()) {
                    { TextButton(onClick = { onQuery("") }) { Text("Clear") } }
                } else {
                    null
                },
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { onCategory(category) },
                        label = { Text(category) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${visible.size} of ${state.properties.size} properties",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val persistentCount = state.properties.count { it.persistentValue != null }
                if (persistentCount > 0) {
                    Text(
                        "$persistentCount persistent",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    "No properties match these filters.",
                    modifier = Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visible, key = { it.name }) { property ->
            PropertyCard(property = property, onClick = { onEdit(property) })
        }
    }
}

@Composable
private fun RootStatusCard(
    rootGranted: Boolean,
    resetPropAvailable: Boolean,
    implementation: String?,
    detail: String?,
    backupCount: Int,
    onRefresh: () -> Unit,
) {
    val ready = rootGranted && resetPropAvailable
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ready) "Root editor ready" else "Editing unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            ready -> "resetprop detected${implementation?.let { " · $it" } ?: ""}"
                            rootGranted -> "Root granted, but resetprop was not found"
                            else -> "Grant root to enable runtime and persistent edits"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onRefresh) { Text("Check") }
            }
            detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "$backupCount automatic snapshots stored · newest 10 retained",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PropertyCard(property: AndroidProperty, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    property.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                RiskBadge(property.risk)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                property.value.ifEmpty { "(empty)" },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                property.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelBadge(property.category)
                if (property.persistentValue != null) LabelBadge("Persistent override")
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: PropertyRisk) {
    if (risk == PropertyRisk.NORMAL) return
    val (label, background, foreground) = when (risk) {
        PropertyRisk.CAUTION -> Triple(
            "Caution",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        PropertyRisk.HIGH -> Triple(
            "High risk",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        PropertyRisk.CRITICAL -> Triple(
            "Critical",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
        )
        PropertyRisk.NORMAL -> return
    }
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LabelBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EditPropertyDialog(
    property: AndroidProperty,
    latestBackupValue: String?,
    rootReady: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, ApplyMode) -> Unit,
    onRemovePersistent: () -> Unit,
) {
    var value by rememberSaveable(property.name) { mutableStateOf(property.value) }
    var mode by rememberSaveable(property.name) { mutableStateOf(ApplyMode.RUNTIME) }
    var confirmed by rememberSaveable(property.name) { mutableStateOf(false) }
    val needsConfirmation = property.risk == PropertyRisk.HIGH || property.risk == PropertyRisk.CRITICAL
    val changed = value != property.value || mode == ApplyMode.PERSISTENT

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit property") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(property.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    property.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Value") },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text("Blank is allowed; line breaks are not") },
                )
                if (latestBackupValue != null && latestBackupValue != value) {
                    TextButton(onClick = { value = latestBackupValue }) {
                        Text("Use value from newest snapshot")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Apply mode", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ApplyMode.RUNTIME,
                        onClick = { mode = ApplyMode.RUNTIME },
                        label = { Text("Runtime") },
                    )
                    FilterChip(
                        selected = mode == ApplyMode.PERSISTENT,
                        onClick = { mode = ApplyMode.PERSISTENT },
                        label = { Text("Persistent") },
                    )
                }
                Text(
                    if (mode == ApplyMode.RUNTIME) {
                        "Changes the live value and resets on reboot."
                    } else {
                        "Changes the live value and stores it in a root module for future boots."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (property.risk != PropertyRisk.NORMAL) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = if (needsConfirmation) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                when (property.risk) {
                                    PropertyRisk.CRITICAL -> "Critical: this can prevent Android or apps from starting."
                                    PropertyRisk.HIGH -> "High risk: this affects platform security or compatibility behaviour."
                                    PropertyRisk.CAUTION -> "Caution: apps and services may use this as device identity."
                                    PropertyRisk.NORMAL -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (needsConfirmation) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = confirmed, onClick = { confirmed = !confirmed }),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                                    Text("I understand the boot and compatibility risk")
                                }
                            }
                        }
                    }
                }
                if (!rootReady) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Root plus a resetprop implementation is required before this can be saved.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                property.persistentValue?.let { persistent ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("Saved persistent value", style = MaterialTheme.typography.labelLarge)
                    Text(persistent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRemovePersistent, enabled = !saving) {
                        Text("Remove persistent override")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(value, mode) },
                enabled = rootReady && !saving && changed && (!needsConfirmation || confirmed),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (saving) "Saving" else "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About RO Properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The app discovers every ro.* property exposed by getprop on the current device. Known keys have specific descriptions; OEM-only keys use a clearly scoped prefix description.",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Android makes ro.* values immutable after creation. This app therefore requires root and resetprop. Persistent mode creates a small module at /data/adb/modules/roproperties_persist.",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Changing boot, ABI, SDK, security, or identity properties can bootloop the device, break apps, or invalidate compatibility checks. A snapshot is made before every edit, but a custom recovery or known-good boot image is still recommended.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Reading device properties…")
        }
    }
}

@Composable
private fun ErrorScreen(error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Could not load properties", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
