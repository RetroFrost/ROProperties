package dev.retrofrost.roproperties.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.retrofrost.roproperties.io.ImportProfileAnalyzer
import dev.retrofrost.roproperties.io.ImportedPropertyValue
import dev.retrofrost.roproperties.io.PropertyTextFormat
import dev.retrofrost.roproperties.model.ApplyStrategy
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.PropertyCategory
import dev.retrofrost.roproperties.model.PropertyCategoryClassifier
import dev.retrofrost.roproperties.model.PropertyUiItem
import dev.retrofrost.roproperties.model.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ROPropertiesApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selected by remember { mutableStateOf<PropertyUiItem?>(null) }
    var editing by remember { mutableStateOf<PropertyUiItem?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val parsedImport = remember(importText) { PropertyTextFormat.parse(importText) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingExport
        if (uri != null && text != null) {
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                            ?: error("Could not open destination")
                    }
                }.isSuccess
                viewModel.showMessage(if (ok) "Selected properties exported to TXT." else "Could not export TXT.")
                if (ok) viewModel.cancelSelection()
            }
        }
        pendingExport = null
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not read file")
                    }
                }.onSuccess {
                    importText = it
                    showImport = true
                }.onFailure { viewModel.showMessage("Could not read that text file.") }
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.selectionMode,
        drawerContent = {
            AppDrawer(
                state = state,
                onCategory = {
                    viewModel.setCategory(it)
                    scope.launch { drawerState.close() }
                },
                onConfidence = {
                    viewModel.setConfidenceFilter(it)
                    scope.launch { drawerState.close() }
                },
                onImport = {
                    scope.launch { drawerState.close() }
                    showImport = true
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (state.selectionMode) "${state.selectedNames.size} selected" else state.category.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (state.selectionMode) "Select properties to export" else "${state.filteredProperties.size} properties",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        if (state.selectionMode) {
                            TextButton(onClick = viewModel::cancelSelection) { Text("Cancel") }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { TwoLineMenuGlyph() }
                        }
                    },
                    actions = {
                        if (state.selectionMode) {
                            TextButton(onClick = viewModel::toggleSelectAllFiltered) {
                                Text(if (state.allFilteredSelected) "Clear all" else "Select all")
                            }
                        } else {
                            TextButton(onClick = viewModel::beginSelection, enabled = !state.loading) { Text("Select") }
                            TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
                        }
                    },
                )
            },
            bottomBar = {
                if (state.selectionMode) {
                    SelectionBottomBar(state.selectedNames.size) {
                        if (state.selectedProperties.isEmpty()) {
                            viewModel.showMessage("Select at least one property first.")
                        } else {
                            pendingExport = PropertyTextFormat.export(state.selectedProperties.map { it.property })
                            saveLauncher.launch("ROProperties-${state.selectedNames.size}-properties.txt")
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            MainContent(
                state = state,
                modifier = Modifier.padding(padding),
                onQuery = viewModel::setQuery,
                onProperty = { selected = it },
                onToggle = { viewModel.toggleSelection(it.property.name) },
                onReboot = { showRebootConfirm = true },
                onDismissReboot = viewModel::clearRebootRecommendation,
            )
        }
    }

    selected?.let { item ->
        PropertyDetailsSheet(
            item = item,
            state = state,
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

    if (showImport) {
        ImportPropertiesDialog(
            text = importText,
            parsedValues = parsedImport,
            currentPropertyNames = state.properties.mapTo(mutableSetOf()) { it.property.name },
            runtimeAvailable = state.capabilities.resetPropAvailable,
            persistentAvailable = state.capabilities.modulePersistenceAvailable,
            rootAvailable = state.capabilities.rootAvailable,
            applying = state.applying,
            onTextChanged = { importText = it },
            onChooseFile = { openLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
            onPaste = {
                val pasted = clipboard.getText()?.text.orEmpty()
                if (pasted.isBlank()) viewModel.showMessage("Clipboard does not contain text.") else importText = pasted
            },
            onDismiss = { if (!state.applying) showImport = false },
            onApply = { values, mode ->
                viewModel.applyImported(values, mode)
                showImport = false
            },
        )
    }

    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text("Reboot device?") },
            text = { Text("A reboot lets boot-time and cached Android identity values be read again by fresh framework and app processes. Unsaved work in other apps will be lost.") },
            confirmButton = {
                Button(onClick = {
                    showRebootConfirm = false
                    viewModel.rebootDevice()
                }) { Text("Reboot now") }
            },
            dismissButton = { TextButton(onClick = { showRebootConfirm = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun MainContent(
    state: MainUiState,
    modifier: Modifier,
    onQuery: (String) -> Unit,
    onProperty: (PropertyUiItem) -> Unit,
    onToggle: (PropertyUiItem) -> Unit,
    onReboot: () -> Unit,
    onDismissReboot: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        SearchField(state.query, onQuery)
        Spacer(Modifier.height(12.dp))

        if (state.rebootRecommended && !state.selectionMode) {
            RebootBanner(state.rebootReason, onReboot, onDismissReboot)
            Spacer(Modifier.height(12.dp))
        }

        if (state.category != PropertyCategory.ALL) {
            CategoryIntro(state.category)
            Spacer(Modifier.height(12.dp))
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.filteredProperties.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching properties") }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.filteredProperties, key = { it.property.name }) { item ->
                    PropertyRow(
                        item = item,
                        selectionMode = state.selectionMode,
                        selected = item.property.name in state.selectedNames,
                        onClick = { if (state.selectionMode) onToggle(item) else onProperty(item) },
                        onToggle = { onToggle(item) },
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search name, value, meaning or editability") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        trailingIcon = if (query.isNotEmpty()) {
            { Text("×", modifier = Modifier.clickable { onQuery("") }.padding(8.dp)) }
        } else null,
    )
}

@Composable
private fun RebootBanner(reason: String?, onReboot: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Reboot recommended", fontWeight = FontWeight.SemiBold)
            Text(
                reason ?: "Boot/cache-sensitive overrides are pending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReboot) { Text("Reboot now") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PropertyRow(
    item: PropertyUiItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val serviceFrameworkMismatch = item.frameworkValue != null && item.frameworkValue != item.property.value
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        item.property.name,
                        modifier = Modifier.fillMaxWidth(0.78f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.explanation.risk.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.explanation.risk >= RiskLevel.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.property.value.ifEmpty { "(empty)" },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (serviceFrameworkMismatch) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Framework cache differs",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (item.persistentOverride != null && item.persistentOverride != item.property.value) {
                    Spacer(Modifier.height(5.dp))
                    Text("Persistent override pending next boot", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniLabel(item.policy.editability.label)
                    MiniLabel(item.policy.strategy.label)
                    if (PropertyCategoryClassifier.isSpoofingProperty(item.property.name)) MiniLabel("Identity")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyDetailsSheet(
    item: PropertyUiItem,
    state: MainUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(item.property.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(item.property.value.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            RealityCheckCard(item)
            Spacer(Modifier.height(18.dp))
            DetailSection("What this property means", item.explanation.propertyMeaning)
            DetailSection("What this value means", item.explanation.valueMeaning)
            DetailSection("Override reality", item.policy.warning)

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            MetadataRow("Editability", item.policy.editability.label)
            MetadataRow("Apply strategy", item.policy.strategy.label)
            MetadataRow("Descriptive string", if (item.policy.descriptiveOnly) "Yes — changing the string does not create the represented hardware/security state." else "Not known to be purely descriptive")
            MetadataRow("Origin", item.explanation.origin)
            MetadataRow("Confidence", item.explanation.confidence.label)
            MetadataRow("Risk", item.explanation.risk.label)
            MetadataRow("Likely consumers", item.explanation.consumers)

            Spacer(Modifier.height(10.dp))
            when {
                !item.policy.canOverride -> Text("ROProperties intentionally blocks overriding this property.", color = MaterialTheme.colorScheme.error)
                !state.capabilities.rootAvailable -> Text("Root is unavailable; inspection only.", color = MaterialTheme.colorScheme.error)
                !state.capabilities.resetPropAvailable && !state.capabilities.modulePersistenceAvailable -> Text("Root works, but no supported override mechanism is available.", color = MaterialTheme.colorScheme.error)
                else -> Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Override property") }
            }
        }
    }
}

@Composable
private fun RealityCheckCard(item: PropertyUiItem) {
    val framework = item.frameworkValue
    val mismatch = framework != null && framework != item.property.value
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (mismatch) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Effective value check", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ValueLine("Property service / getprop", item.property.value.ifEmpty { "(empty)" })
            if (framework != null) {
                ValueLine("ROProperties framework cache", framework.ifEmpty { "(empty)" })
                Text(
                    if (mismatch) "Mismatch: changing getprop has not changed the already-running framework view in this app process." else "The mapped framework value currently agrees with getprop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mismatch) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("No direct Android Build.* mapping is available for this property; getprop alone cannot prove the consumer changed behaviour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.persistentOverride?.let {
                Spacer(Modifier.height(8.dp))
                ValueLine("Saved next-boot override", it)
            }
        }
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(7.dp))
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
        mutableStateOf(preferredMode(item, runtimeAvailable, persistentAvailable))
    }
    var acknowledged by rememberSaveable(item.property.name) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Override property") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(item.property.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (item.policy.strategy == ApplyStrategy.NEXT_BOOT) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${item.policy.editability.label} • ${item.policy.strategy.label}", fontWeight = FontWeight.SemiBold)
                        Text(item.policy.warning, style = MaterialTheme.typography.bodySmall)
                        if (item.policy.strategy == ApplyStrategy.NEXT_BOOT) {
                            Spacer(Modifier.height(5.dp))
                            Text("Persistent + reboot is recommended for this property.", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value, { value = it }, label = { Text("New value") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(14.dp))
                Text("Apply strategy", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                EditMode.entries.forEach { candidate ->
                    val supported = when (candidate) {
                        EditMode.RUNTIME -> runtimeAvailable
                        EditMode.PERSISTENT -> persistentAvailable
                        EditMode.BOTH -> runtimeAvailable && persistentAvailable
                    }
                    EditModeChoice(candidate, mode == candidate, supported) { mode = candidate }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I understand that a property string and the real subsystem/hardware state can differ.")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(value, mode) }, enabled = acknowledged && !applying) {
                if (applying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !applying) { Text("Cancel") } },
    )
}

private fun preferredMode(item: PropertyUiItem, runtimeAvailable: Boolean, persistentAvailable: Boolean): EditMode = when {
    item.policy.strategy == ApplyStrategy.NEXT_BOOT && persistentAvailable -> EditMode.PERSISTENT
    runtimeAvailable && persistentAvailable -> EditMode.BOTH
    persistentAvailable -> EditMode.PERSISTENT
    else -> EditMode.RUNTIME
}

@Composable
private fun ImportPropertiesDialog(
    text: String,
    parsedValues: List<ImportedPropertyValue>,
    currentPropertyNames: Set<String>,
    runtimeAvailable: Boolean,
    persistentAvailable: Boolean,
    rootAvailable: Boolean,
    applying: Boolean,
    onTextChanged: (String) -> Unit,
    onChooseFile: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (List<ImportedPropertyValue>, EditMode) -> Unit,
) {
    val analysis = remember(parsedValues) { ImportProfileAnalyzer.analyze(parsedValues) }
    var mode by rememberSaveable(parsedValues.size, analysis.nextBootCount) {
        mutableStateOf(
            when {
                analysis.nextBootCount > 0 && persistentAvailable -> EditMode.PERSISTENT
                runtimeAvailable && persistentAvailable -> EditMode.BOTH
                persistentAvailable -> EditMode.PERSISTENT
                else -> EditMode.RUNTIME
            }
        )
    }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val existing = parsedValues.count { it.name in currentPropertyNames }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import property profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Import is treated as a profile, not a blind list of magic switches. ROProperties checks for mixed identity and blocks properties classified as read-only or dangerous.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseFile) { Text("Choose file") }
                    OutlinedButton(onClick = onPaste) { Text("Paste") }
                    if (text.isNotEmpty()) TextButton(onClick = { onTextChanged("") }) { Text("Clear") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    placeholder = { Text("ro.product.model = SM-S901B") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(10.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${parsedValues.size} valid ro.* values", fontWeight = FontWeight.SemiBold)
                        Text("$existing already exist on this device. ${analysis.nextBootCount} are boot/cache-sensitive. ${analysis.blockedCount} are blocked by policy.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                analysis.warnings.forEach { warning ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (analysis.mixedIdentity) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(warning, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Apply strategy", fontWeight = FontWeight.SemiBold)
                EditMode.entries.forEach { candidate ->
                    val supported = when (candidate) {
                        EditMode.RUNTIME -> runtimeAvailable
                        EditMode.PERSISTENT -> persistentAvailable
                        EditMode.BOTH -> runtimeAvailable && persistentAvailable
                    }
                    EditModeChoice(candidate, mode == candidate, supported) { mode = candidate }
                }
                if (!rootAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text("Root is required to apply overrides.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I reviewed the profile warnings and understand that strings do not change the underlying hardware or cryptographic state.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(parsedValues, mode) },
                enabled = parsedValues.isNotEmpty() && rootAvailable && acknowledged && !applying,
            ) {
                if (applying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Apply ${parsedValues.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !applying) { Text("Cancel") } },
    )
}

@Composable
private fun EditModeChoice(mode: EditMode, selected: Boolean, enabled: Boolean, onSelected: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = { if (enabled) onSelected() },
        enabled = enabled,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = { if (enabled) onSelected() }, enabled = enabled)
            Column(Modifier.padding(top = 6.dp)) {
                Text(mode.label, fontWeight = FontWeight.SemiBold)
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppDrawer(
    state: MainUiState,
    onCategory: (PropertyCategory) -> Unit,
    onConfidence: (KnowledgeConfidence?) -> Unit,
    onImport: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("ROProperties", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Property inspector + override manager", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            DrawerSection("Properties")
            PropertyCategory.entries.forEach { category ->
                DrawerRow(category.label, "${state.countFor(category)}", state.category == category) { onCategory(category) }
            }
            Spacer(Modifier.height(14.dp))
            DrawerSection("Confidence")
            DrawerRow("Any confidence", null, state.confidenceFilter == null) { onConfidence(null) }
            KnowledgeConfidence.entries.forEach { confidence ->
                DrawerRow(confidence.label, null, state.confidenceFilter == confidence) { onConfidence(confidence) }
            }
            Spacer(Modifier.height(14.dp))
            DrawerSection("Tools")
            DrawerRow("Import property profile", "TXT / paste", false, onImport)
            Spacer(Modifier.height(14.dp))
            DrawerSection("Apply modes")
            EditMode.entries.forEach { mode -> ModeInfoCard(mode) }
            Spacer(Modifier.height(14.dp))
            RootStatusCard(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CategoryIntro(category: PropertyCategory) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp)) {
            Text(category.label, fontWeight = FontWeight.SemiBold)
            Text(category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (category == PropertyCategory.SPOOFING) {
                Spacer(Modifier.height(6.dp))
                Text("Identity strings are treated as boot/cache-sensitive. Persistent + reboot is the reliable default; a live getprop change is not considered proof that Android or Play services changed identity.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RootStatusCard(state: MainUiState) {
    val caps = state.capabilities
    val title = when {
        state.loading -> "Checking root"
        !caps.rootAvailable -> "Inspection only"
        caps.resetPropAvailable && caps.modulePersistenceAvailable -> "Live + persistent available"
        caps.resetPropAvailable -> "Live overrides available"
        caps.modulePersistenceAvailable -> "Next-boot overrides available"
        else -> "Override unavailable"
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(if (caps.rootAvailable) caps.framework else "No root access detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeInfoCard(mode: EditMode) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(10.dp)) {
            Text(mode.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DrawerSection(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DrawerRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SelectionBottomBar(selectedCount: Int, onExport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$selectedCount selected")
            Button(onClick = onExport, enabled = selectedCount > 0) { Text("Export TXT") }
        }
    }
}

@Composable
private fun MiniLabel(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DetailSection(title: String, text: String) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
    Text(text)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun TwoLineMenuGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(19.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface))
        Box(Modifier.width(13.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface))
    }
}
