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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abandonsearch.hazardgrid.core.decodeBase64Image
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.AgeFilter
import com.abandonsearch.hazardgrid.domain.FloorsFilter
import com.abandonsearch.hazardgrid.domain.RatingFilter
import com.abandonsearch.hazardgrid.domain.ScaleFilter
import com.abandonsearch.hazardgrid.domain.SortOption
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import com.abandonsearch.hazardgrid.ui.theme.AccentPrimary
import com.abandonsearch.hazardgrid.ui.theme.AccentStrong
import com.abandonsearch.hazardgrid.ui.theme.NightOverlay
import com.abandonsearch.hazardgrid.ui.theme.SurfaceBorder
import com.abandonsearch.hazardgrid.ui.theme.TextMuted
import com.abandonsearch.hazardgrid.ui.theme.TextPrimary
import com.abandonsearch.hazardgrid.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.buildList

private const val HEADER_ITEM_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPanel(
    uiState: HazardUiState,
    isCompact: Boolean,
    onSearchChange: (String) -> Unit,
    onFloorsChange: (FloorsFilter) -> Unit,
    onSecurityChange: (ScaleFilter) -> Unit,
    onInteriorChange: (ScaleFilter) -> Unit,
    onAgeChange: (AgeFilter) -> Unit,
    onRatingChange: (RatingFilter) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onResultSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.activePlaceId) {
        val activeId = uiState.activePlaceId ?: return@LaunchedEffect
        val index = uiState.searchResults.indexOfFirst { it.id == activeId }
        if (index >= 0) {
            listState.animateScrollToItem(HEADER_ITEM_COUNT + index)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.activePlace == null) {
            HazardSearchSection(
                query = uiState.filterState.query,
                onSearchChange = onSearchChange
            )
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
            item {
                HazardFilterSection(
                    isCompact = isCompact,
                    onFloorsChange = onFloorsChange,
                    onSecurityChange = onSecurityChange,
                    onInteriorChange = onInteriorChange,
                    onAgeChange = onAgeChange,
                    onRatingChange = onRatingChange,
                    onSortChange = onSortChange,
                    uiState = uiState
                )
            }
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
                            "Incoming signals… stand by."
                        } else {
                            "No signal matches the current filters."
                        },
                        color = TextMuted,
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
                        onClick = { onResultSelected(place.id) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HazardSearchSection(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Search the grid",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = query,
            onValueChange = onSearchChange,
            placeholder = { Text("Search by title, intel, address", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Search
            ),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth()
        )
        HazardDivider()
    }
}

@Composable
private fun HazardFilterSection(
    isCompact: Boolean,
    onFloorsChange: (FloorsFilter) -> Unit,
    onSecurityChange: (ScaleFilter) -> Unit,
    onInteriorChange: (ScaleFilter) -> Unit,
    onAgeChange: (AgeFilter) -> Unit,
    onRatingChange: (RatingFilter) -> Unit,
    onSortChange: (SortOption) -> Unit,
    uiState: HazardUiState,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Filter anomalies",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        val filterState = uiState.filterState
        val activeFilters = remember(filterState) {
            buildList {
                if (filterState.floors != FloorsFilter.ANY) {
                    labelFor(filterState.floors, floorOptions)?.let { add("Floors • $it") }
                }
                if (filterState.security != ScaleFilter.ANY) {
                    labelFor(filterState.security, scaleOptions)?.let { add("Security • $it") }
                }
                if (filterState.interior != ScaleFilter.ANY) {
                    labelFor(filterState.interior, scaleOptions)?.let { add("Interior • $it") }
                }
                if (filterState.age != AgeFilter.ANY) {
                    labelFor(filterState.age, ageOptions)?.let { add("Age • $it") }
                }
                if (filterState.rating != RatingFilter.ANY) {
                    labelFor(filterState.rating, ratingOptions)?.let { add("Rating • $it") }
                }
                if (filterState.sort != SortOption.RELEVANCE) {
                    labelFor(filterState.sort, sortOptions)?.let { add("Sort • $it") }
                }
            }
        }
        if (activeFilters.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                activeFilters.forEach { label ->
                    HazardFilterChip(text = label)
                }
            }
        }
        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterDropdown(
                    label = "Floors",
                    options = floorOptions,
                    selected = uiState.filterState.floors,
                    onOptionSelected = onFloorsChange,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterDropdown(
                    label = "Security",
                    options = scaleOptions,
                    selected = uiState.filterState.security,
                    onOptionSelected = onSecurityChange,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterDropdown(
                    label = "Interior",
                    options = scaleOptions,
                    selected = uiState.filterState.interior,
                    onOptionSelected = onInteriorChange,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterDropdown(
                    label = "Building age",
                    options = ageOptions,
                    selected = uiState.filterState.age,
                    onOptionSelected = onAgeChange,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterDropdown(
                    label = "Rating",
                    options = ratingOptions,
                    selected = uiState.filterState.rating,
                    onOptionSelected = onRatingChange,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterDropdown(
                    label = "Sort",
                    options = sortOptions,
                    selected = uiState.filterState.sort,
                    onOptionSelected = onSortChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterDropdown(
                    label = "Floors",
                    options = floorOptions,
                    selected = uiState.filterState.floors,
                    onOptionSelected = onFloorsChange,
                    modifier = Modifier.weight(1f)
                )
                FilterDropdown(
                    label = "Security",
                    options = scaleOptions,
                    selected = uiState.filterState.security,
                    onOptionSelected = onSecurityChange,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterDropdown(
                    label = "Interior",
                    options = scaleOptions,
                    selected = uiState.filterState.interior,
                    onOptionSelected = onInteriorChange,
                    modifier = Modifier.weight(1f)
                )
                FilterDropdown(
                    label = "Building age",
                    options = ageOptions,
                    selected = uiState.filterState.age,
                    onOptionSelected = onAgeChange,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterDropdown(
                    label = "Rating",
                    options = ratingOptions,
                    selected = uiState.filterState.rating,
                    onOptionSelected = onRatingChange,
                    modifier = Modifier.weight(1f)
                )
                FilterDropdown(
                    label = "Sort",
                    options = sortOptions,
                    selected = uiState.filterState.sort,
                    onOptionSelected = onSortChange,
                    modifier = Modifier.weight(1f)
                )
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        val totalText = if (totalCount > 0) "$resultCount / $totalCount" else resultCount.toString()
        Text(
            text = "Active signals: $totalText",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
        HazardDivider()
    }
}

@Composable
private fun HazardFilterChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = NightOverlay.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, SurfaceBorder),
        tonalElevation = 4.dp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
    ) {
        Text(
            text = text,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

private fun <T> labelFor(value: T, options: List<FilterOption<T>>): String? =
    options.firstOrNull { it.value == value }?.label

@Composable
private fun HazardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SurfaceBorder)
    )
}

@Composable
private fun ResultCard(
    place: Place,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageState by produceState<ImageBitmap?>(initialValue = null, place.images) {
        value = withContext(Dispatchers.Default) {
            place.images.firstOrNull()?.let { decodeBase64Image(it) }
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        tonalElevation = if (isActive) 18.dp else 6.dp,
        shadowElevation = if (isActive) 24.dp else 10.dp,
        border = BorderStroke(
            1.dp,
            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
        ),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (isActive) 0.98f else 0.94f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val mapsUrl = buildMapsUrl(place)
                if (mapsUrl != null) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Open maps", fontWeight = FontWeight.SemiBold)
                    }
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

private val floorOptions = listOf(
    FilterOption(FloorsFilter.ANY, "Any floors"),
    FilterOption(FloorsFilter.LOW, "1 – 5 floors"),
    FilterOption(FloorsFilter.MID, "6 – 7 floors"),
    FilterOption(FloorsFilter.HIGH, "8 – 12 floors"),
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
    FilterOption(AgeFilter.ANY, "Any"),
    FilterOption(AgeFilter.NEW, "0 – 2"),
    FilterOption(AgeFilter.RECENT, "3 – 4"),
    FilterOption(AgeFilter.CLASSIC, "5 – 7"),
    FilterOption(AgeFilter.HERITAGE, "8+"),
    FilterOption(AgeFilter.UNKNOWN, "Unknown")
)

private val ratingOptions = listOf(
    FilterOption(RatingFilter.ANY, "Any rating"),
    FilterOption(RatingFilter.FOUR_PLUS, "Rating ≥ 4"),
    FilterOption(RatingFilter.SIX_PLUS, "Rating ≥ 6"),
    FilterOption(RatingFilter.EIGHT_PLUS, "Rating ≥ 8"),
    FilterOption(RatingFilter.NINE_PLUS, "Rating ≥ 9"),
    FilterOption(RatingFilter.UNKNOWN, "No rating")
)

private val sortOptions = listOf(
    FilterOption(SortOption.RELEVANCE, "Relevance"),
    FilterOption(SortOption.DISTANCE, "Distance"),
    FilterOption(SortOption.RATING, "Rating"),
    FilterOption(SortOption.SECURITY, "Security")
)
