package dev.retrofrost.roproperties.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.PropertyCategory
import dev.retrofrost.roproperties.model.PropertyCategoryClassifier
import dev.retrofrost.roproperties.model.PropertyUiItem
import dev.retrofrost.roproperties.model.RiskLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ROPropertiesApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<PropertyUiItem?>(null) }
    var editing by remember { mutableStateOf<PropertyUiItem?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            AppDrawer(
                state = state,
                onCategorySelected = { category ->
                    viewModel.setCategory(category)
                    scope.launch { drawerState.close() }
                },
                onConfidenceSelected = { confidence ->
                    viewModel.setConfidenceFilter(confidence)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.category.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${state.filteredProperties.size} properties",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            TwoLineMenuGlyph()
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
            MainContent(
                state = state,
                modifier = Modifier.padding(padding),
                onQueryChanged = viewModel::setQuery,
                onPropertySelected = { selected = it },
            )
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
private fun AppDrawer(
    state: MainUiState,
    onCategorySelected: (PropertyCategory) -> Unit,
    onConfidenceSelected: (KnowledgeConfidence?) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(320.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                "ROProperties",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Android property editor",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            DrawerSectionTitle("Properties")
            PropertyCategory.entries.forEach { category ->
                DrawerRow(
                    title = category.label,
                    subtitle = "${state.countFor(category)}",
                    selected = state.category == category,
                    onClick = { onCategorySelected(category) },
                )
            }

            Spacer(Modifier.height(18.dp))
            DrawerSectionTitle("Confidence")
            DrawerRow(
                title = "Any confidence",
                selected = state.confidenceFilter == null,
                onClick = { onConfidenceSelected(null) },
            )
            KnowledgeConfidence.entries.forEach { confidence ->
                DrawerRow(
                    title = confidence.label,
                    selected = state.confidenceFilter == confidence,
                    onClick = { onConfidenceSelected(confidence) },
                )
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            RootStatusCard(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onQueryChanged: (String) -> Unit,
    onPropertySelected: (PropertyUiItem) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SearchField(
            query = state.query,
            onQueryChanged = onQueryChanged,
        )
        Spacer(Modifier.height(14.dp))

        if (state.category != PropertyCategory.ALL) {
            CategoryIntro(state.category)
            Spacer(Modifier.height(14.dp))
        }

        when {
            state.loading -> LoadingState()
            state.filteredProperties.isEmpty() -> EmptyState(state)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.filteredProperties, key = { it.property.name }) { item ->
                    PropertyRow(item, onClick = { onPropertySelected(item) })
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search properties") },
        leadingIcon = {
            Text(
                "⌕",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                Text(
                    "×",
                    modifier = Modifier.clickable { onQueryChanged("") }.padding(8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun CategoryIntro(category: PropertyCategory) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                category.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                category.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (category == PropertyCategory.SPOOFING) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "This groups identity-facing properties only. Changing them can affect compatibility and does not guarantee any integrity or app-compatibility result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Reading Android properties…")
    }
}

@Composable
private fun EmptyState(state: MainUiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No properties here",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.query.isNotBlank()) "Try another search." else "This device does not expose matching ro.* values.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PropertyRow(item: PropertyUiItem, onClick: () -> Unit) {
    val isSpoofing = PropertyCategoryClassifier.isSpoofingProperty(item.property.name)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.property.name,
                    modifier = Modifier.fillMaxWidth(0.78f),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.explanation.risk.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.explanation.risk >= RiskLevel.HIGH) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                item.property.value.ifEmpty { "(empty)" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                item.explanation.propertyMeaning,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniLabel(item.explanation.confidence.label)
                if (isSpoofing) MiniLabel("Spoofing")
            }
        }
    }
}

@Composable
private fun MiniLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RootStatusCard(state: MainUiState) {
    val caps = state.capabilities
    val title = when {
        state.loading -> "Checking root"
        !caps.rootAvailable -> "Read-only"
        caps.resetPropAvailable && caps.modulePersistenceAvailable -> "Runtime + persistent"
        caps.resetPropAvailable -> "Runtime editing"
        caps.modulePersistenceAvailable -> "Persistent editing"
        else -> "Editing unavailable"
    }
    val subtitle = when {
        state.loading -> "Detecting root capabilities…"
        !caps.rootAvailable -> "No root access detected"
        else -> caps.framework
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (caps.rootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TwoLineMenuGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(19.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface),
        )
        Box(
            Modifier
                .width(13.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface),
        )
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
            Text(
                item.property.name,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.property.value.ifEmpty { "(empty)" },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            DetailSection("What this property means", item.explanation.propertyMeaning)
            DetailSection("What this value means", item.explanation.valueMeaning)

            if (item.explanation.knownValues.isNotEmpty()) {
                Text("Known values", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                item.explanation.knownValues.forEach { (value, meaning) ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text(meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
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
            when {
                !canEdit -> Text(
                    "Root is not available, so this device is currently read-only.",
                    color = MaterialTheme.colorScheme.error,
                )
                !runtimeAvailable && !persistentAvailable -> Text(
                    "Root works, but neither resetprop nor a module persistence directory is available.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
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
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        title = { Text("Edit property") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    item.property.name,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
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
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(14.dp))
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
                        Text(
                            candidate.label,
                            color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                if (applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Apply")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying) { Text("Cancel") }
        },
    )
}
