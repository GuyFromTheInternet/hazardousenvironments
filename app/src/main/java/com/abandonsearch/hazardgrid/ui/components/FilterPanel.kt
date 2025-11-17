package com.abandonsearch.hazardgrid.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abandonsearch.hazardgrid.core.decodeBase64Image
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.data.settings.MapApp
import com.abandonsearch.hazardgrid.domain.FilterState
import com.abandonsearch.hazardgrid.domain.FloorsFilter
import com.abandonsearch.hazardgrid.domain.RatingFilter
import com.abandonsearch.hazardgrid.domain.ScaleFilter
import com.abandonsearch.hazardgrid.domain.SortOption
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HEADER_ITEM_COUNT = 2

enum class FilterDialogType {
    FLOORS,
    SECURITY,
    INTERIOR,
    AGE,
    RATING,
    SORT
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilterPanel(
    uiState: HazardUiState,
    listState: LazyListState,
    onResultSelected: (Int) -> Unit,
    mapApp: MapApp,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(uiState.activePlaceId) {
        val activeId = uiState.activePlaceId ?: return@LaunchedEffect
        val index = uiState.searchResults.indexOfFirst { it.id == activeId }
        if (index >= 0) {
            listState.animateScrollToItem(HEADER_ITEM_COUNT + index)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                HazardSectionTitle(
                    label = "Intel feed",
                    resultCount = uiState.searchResults.size,
                    totalCount = uiState.totalValid
                )
            }
            if (uiState.searchResults.isEmpty()) {
                item {
                    Text(
                        text = if (uiState.filterState.query.isBlank() && !uiState.hasFilters) {
                            "Incoming signals... stand by."
                        } else {
                            "No signal matches the current filters."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            } else {
                itemsIndexed(uiState.searchResults, key = { _, place -> place.id }) { _, place ->
                    val isActive = uiState.activePlaceId == place.id
                    ResultCard(
                        place = place,
                        isActive = isActive,
                        mapApp = mapApp,
                        onClick = { onResultSelected(place.id) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun FilterChipsRow(
    filterState: FilterState,
    modifier: Modifier = Modifier,
    onChipClick: (FilterDialogType) -> Unit,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilterChipDisplay.create(filterState).forEach { chip ->
            AssistChip(
                onClick = { onChipClick(chip.type) },
                modifier = Modifier.heightIn(min = 48.dp),
                label = {
                    Text(
                        text = "${chip.label}: ${chip.value}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun FilterDialogHost(
    dialogType: FilterDialogType,
    filterState: FilterState,
    onFloorsChange: (FloorsFilter) -> Unit,
    onSecurityChange: (ScaleFilter) -> Unit,
    onInteriorChange: (ScaleFilter) -> Unit,
    onAgeChange: (com.abandonsearch.hazardgrid.domain.AgeFilter) -> Unit,
    onRatingChange: (RatingFilter) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialogType) {
        FilterDialogType.FLOORS -> FilterSelectionDialog(
            title = "Floors",
            selected = filterState.floors,
            options = floorOptions,
            onOptionSelected = {
                onFloorsChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        FilterDialogType.SECURITY -> FilterSelectionDialog(
            title = "Security",
            selected = filterState.security,
            options = scaleOptions,
            onOptionSelected = {
                onSecurityChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        FilterDialogType.INTERIOR -> FilterSelectionDialog(
            title = "Interior",
            selected = filterState.interior,
            options = scaleOptions,
            onOptionSelected = {
                onInteriorChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        FilterDialogType.AGE -> FilterSelectionDialog(
            title = "Building age",
            selected = filterState.age,
            options = ageOptions,
            onOptionSelected = {
                onAgeChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        FilterDialogType.RATING -> FilterSelectionDialog(
            title = "Rating",
            selected = filterState.rating,
            options = ratingOptions,
            onOptionSelected = {
                onRatingChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        FilterDialogType.SORT -> FilterSelectionDialog(
            title = "Sort order",
            selected = filterState.sort,
            options = sortOptions,
            onOptionSelected = {
                onSortChange(it)
                onDismiss()
            },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun <T> FilterSelectionDialog(
    title: String,
    selected: T,
    options: List<FilterOption<T>>,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                tonalElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Refine the intel feed instantly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        options.forEach { option ->
                            val isSelected = option.value == selected
                            val shape = RoundedCornerShape(22.dp)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .clickable { onOptionSelected(option.value) },
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                                },
                                tonalElevation = if (isSelected) 6.dp else 0.dp,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 18.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = if (isSelected) "Applied" else "Tap to apply",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onOptionSelected(option.value) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HazardSectionTitle(
    label: String,
    resultCount: Int,
    totalCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        val totalText = if (totalCount > 0) "$resultCount / $totalCount" else resultCount.toString()
        Text(
            text = "Active signals: $totalText",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultCard(
    place: Place,
    isActive: Boolean,
    mapApp: MapApp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val imageState by produceState<ImageBitmap?>(initialValue = null, place.images) {
        value = withContext(Dispatchers.Default) {
            place.images.firstOrNull()?.let { decodeBase64Image(it) }
        }
    }

    val cardShape = RoundedCornerShape(24.dp)
    val cardColor = if (isActive) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(18.dp)
    } else {
        Color.Transparent
    }
    val borderStroke = if (isActive) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    } else {
        null
    }
    Surface(
        shape = cardShape,
        tonalElevation = if (isActive) 18.dp else 0.dp,
        shadowElevation = 0.dp,
        border = borderStroke,
        color = cardColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = place.title.ifBlank { "Unknown site" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (place.address.isNotBlank()) {
                    Text(
                        text = place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (place.description.isNotBlank()) {
                Text(
                    text = place.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PlaceMetrics(place)
            imageState?.let { imageBitmap ->
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Preview image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            val coords = formatCoordinates(place)
            val mapsUrl = buildMapsUrl(place, mapApp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = coords ?: "No coordinates",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (mapsUrl != null || coords != null) {
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.LeadingButton(
                                onClick = {
                                    coords?.let { clipboard.setText(AnnotatedString(it)) }
                                },
                                enabled = coords != null
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy coordinates",
                                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Coordinates", fontWeight = FontWeight.SemiBold)
                            }
                        },
                        trailingButton = {
                            SplitButtonDefaults.TrailingButton(
                                onClick = {
                                    mapsUrl?.let {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                                        context.startActivity(intent)
                                    }
                                },
                                enabled = mapsUrl != null
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Map,
                                    contentDescription = "Open maps",
                                    modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize)
                                )
                            }
                        }
                    )
                }
                if (place.url.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(place.url))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("Open intel")
                    }
                }
            }
        }
    }
}

private fun formatCoordinates(place: Place): String? {
    val lat = place.lat ?: return null
    val lon = place.lon ?: return null
    return "${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
}

private data class FilterChipDisplay(
    val type: FilterDialogType,
    val label: String,
    val value: String,
) {
    companion object {
        fun create(state: FilterState): List<FilterChipDisplay> = listOf(
            FilterChipDisplay(FilterDialogType.FLOORS, "Floors", labelFor(state.floors, floorOptions)),
            FilterChipDisplay(FilterDialogType.SECURITY, "Security", labelFor(state.security, scaleOptions)),
            FilterChipDisplay(FilterDialogType.INTERIOR, "Interior", labelFor(state.interior, scaleOptions)),
            FilterChipDisplay(FilterDialogType.AGE, "Age", labelFor(state.age, ageOptions)),
            FilterChipDisplay(FilterDialogType.RATING, "Rating", labelFor(state.rating, ratingOptions)),
            FilterChipDisplay(FilterDialogType.SORT, "Sort", labelFor(state.sort, sortOptions))
        )
    }
}

private fun <T> labelFor(value: T, options: List<FilterOption<T>>): String =
    options.firstOrNull { it.value == value }?.label ?: "Any"

private val floorOptions = listOf(
    FilterOption(FloorsFilter.ANY, "Any floors"),
    FilterOption(FloorsFilter.LOW, "1 - 5 floors"),
    FilterOption(FloorsFilter.MID, "6 - 7 floors"),
    FilterOption(FloorsFilter.HIGH, "8 - 12 floors"),
    FilterOption(FloorsFilter.TOWER, "13+ floors"),
    FilterOption(FloorsFilter.UNKNOWN, "Unknown floors")
)

private val scaleOptions = listOf(
    FilterOption(ScaleFilter.ANY, "Any"),
    FilterOption(ScaleFilter.LOW, "Low"),
    FilterOption(ScaleFilter.MEDIUM, "Medium"),
    FilterOption(ScaleFilter.HIGH, "High"),
    FilterOption(ScaleFilter.UNKNOWN, "Unknown")
)

private val ageOptions = listOf(
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.ANY, "Any"),
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.NEW, "0 - 2"),
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.RECENT, "3 - 4"),
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.CLASSIC, "5 - 7"),
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.HERITAGE, "8+"),
    FilterOption(com.abandonsearch.hazardgrid.domain.AgeFilter.UNKNOWN, "Unknown")
)

private val ratingOptions = listOf(
    FilterOption(RatingFilter.ANY, "Any rating"),
    FilterOption(RatingFilter.FOUR_PLUS, "Rating >= 4"),
    FilterOption(RatingFilter.SIX_PLUS, "Rating >= 6"),
    FilterOption(RatingFilter.EIGHT_PLUS, "Rating >= 8"),
    FilterOption(RatingFilter.NINE_PLUS, "Rating >= 9"),
    FilterOption(RatingFilter.UNKNOWN, "No rating")
)



private val sortOptions = listOf(
    FilterOption(SortOption.RELEVANCE, "Relevance"),
    FilterOption(SortOption.DISTANCE, "Distance"),
    FilterOption(SortOption.RATING, "Rating"),
    FilterOption(SortOption.SECURITY, "Security")
)
