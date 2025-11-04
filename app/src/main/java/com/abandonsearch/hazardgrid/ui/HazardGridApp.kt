package com.abandonsearch.hazardgrid.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.rounded.Close
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.data.PlacesRepository
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.ui.components.ErrorOverlay
import com.abandonsearch.hazardgrid.ui.components.FilterPanel
import com.abandonsearch.hazardgrid.ui.components.LoadingOverlay
import com.abandonsearch.hazardgrid.ui.components.PlaceDetailCard
import com.abandonsearch.hazardgrid.ui.components.WebView
import com.abandonsearch.hazardgrid.ui.map.HazardMap
import com.abandonsearch.hazardgrid.ui.map.rememberLocationHeadingState
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import com.abandonsearch.hazardgrid.ui.theme.AccentPrimary
import com.abandonsearch.hazardgrid.ui.theme.NightBackground
import com.abandonsearch.hazardgrid.ui.theme.NightOverlay
import com.abandonsearch.hazardgrid.ui.theme.SurfaceBorder
import com.abandonsearch.hazardgrid.ui.theme.TextMuted
import com.abandonsearch.hazardgrid.ui.theme.TextPrimary
import com.abandonsearch.hazardgrid.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HazardGridApp() {
    val viewModel = hazardGridViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isCompact = configuration.screenWidthDp < 900
    val sheetPeekHeight = if (isCompact) 168.dp else 240.dp

    val sheetState = rememberStandardBottomSheetState(
        initialValue = if (isCompact) SheetValue.PartiallyExpanded else SheetValue.Expanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val coroutineScope = rememberCoroutineScope()
    var webViewUrl by remember { mutableStateOf<String?>(null) }

    val isSheetExpanded by remember {
        derivedStateOf {
            val current = sheetState.currentValue
            val target = sheetState.targetValue
            current == SheetValue.Expanded || target == SheetValue.Expanded
        }
    }


    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        sheetDragHandle = { HazardSheetHandle() },
        sheetContainerColor = NightOverlay.copy(alpha = 0.97f),
        sheetContentColor = TextPrimary,
        sheetTonalElevation = 14.dp,
        sheetShadowElevation = 32.dp,
        sheetContent = {
            HazardPeninsulaSheet(
                uiState = uiState,
                isCompact = isCompact,
                isExpanded = isSheetExpanded,
                onSearchChange = viewModel::updateQuery,
                onFloorsChange = viewModel::updateFloors,
                onSecurityChange = viewModel::updateSecurity,
                onInteriorChange = viewModel::updateInterior,
                onAgeChange = viewModel::updateAge,
                onRatingChange = viewModel::updateRating,
                onSortChange = viewModel::updateSort,
                onClearFilters = viewModel::clearFilters,
                onResultSelected = { placeId ->
                    viewModel.setActivePlace(placeId, centerOnMap = true)
                    coroutineScope.launch { sheetState.partialExpand() }
                },
                onToggleExpand = {
                    coroutineScope.launch {
                        if (isSheetExpanded) {
                            sheetState.partialExpand()
                        } else {
                            sheetState.expand()
                        }
                    }
                },
                onOpenIntel = { webViewUrl = it },
                onClose = { viewModel.setActivePlace(null, centerOnMap = false) }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val context = LocalContext.current
            val locationPermissions = remember {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            }
            var hasLocationPermission by remember {
                mutableStateOf(checkLocationPermission(context))
            }
            var locationMode by remember { mutableStateOf(LocationMode.Idle) }
            var lastCenteredLocation by remember { mutableStateOf<GeoPoint?>(null) }
            var lastOrientationBearing by remember { mutableStateOf<Float?>(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val grantedByLauncher = result.entries.any { it.value }
                val currentStatus = checkLocationPermission(context)
                hasLocationPermission = grantedByLauncher || currentStatus
                if (hasLocationPermission && locationMode == LocationMode.Idle) {
                    locationMode = LocationMode.Centered
                }
            }

            LaunchedEffect(Unit) {
                hasLocationPermission = checkLocationPermission(context)
            }

            LaunchedEffect(hasLocationPermission) {
                if (!hasLocationPermission) {
                    locationMode = LocationMode.Idle
                }
            }

            val locationHeadingState = rememberLocationHeadingState(
                requestUpdates = locationMode != LocationMode.Idle,
                hasLocationPermission = hasLocationPermission,
            )

            LaunchedEffect(locationMode) {
                if (locationMode != LocationMode.Oriented && lastOrientationBearing != null) {
                    viewModel.sendMapCommand(HazardGridViewModel.MapCommand.ResetOrientation)
                    lastOrientationBearing = null
                }
                if (locationMode == LocationMode.Idle) {
                    lastCenteredLocation = null
                }
            }

            LaunchedEffect(locationHeadingState.location, locationMode, hasLocationPermission) {
                if (!hasLocationPermission) return@LaunchedEffect
                val currentLocation = locationHeadingState.location ?: return@LaunchedEffect
                if (locationMode == LocationMode.Centered || locationMode == LocationMode.Oriented) {
                    val shouldCenter = shouldRecentre(lastCenteredLocation, currentLocation)
                    if (shouldCenter) {
                        val zoom = if (locationMode == LocationMode.Centered) {
                            LOCATION_FOCUS_ZOOM
                        } else {
                            ORIENTATION_FOCUS_ZOOM
                        }
                        viewModel.sendMapCommand(
                            HazardGridViewModel.MapCommand.FocusOnLocation(
                                location = currentLocation,
                                zoom = zoom,
                                animate = true
                            )
                        )
                        lastCenteredLocation = currentLocation
                    }
                }
            }

            LaunchedEffect(locationHeadingState.heading, locationMode) {
                if (locationMode == LocationMode.Oriented) {
                    val heading = locationHeadingState.heading ?: return@LaunchedEffect
                    val previous = lastOrientationBearing
                    if (previous == null || bearingDelta(previous, heading) >= ORIENTATION_MIN_DELTA_DEGREES) {
                        viewModel.sendMapCommand(
                            HazardGridViewModel.MapCommand.SetOrientation(
                                bearing = heading
                            )
                        )
                        lastOrientationBearing = heading
                    } else {
                        lastOrientationBearing = heading
                    }
                }
            }

            val onGpsButtonClick: () -> Unit = {
                when (locationMode) {
                    LocationMode.Idle -> {
                        if (hasLocationPermission) {
                            locationMode = LocationMode.Centered
                        } else {
                            permissionLauncher.launch(locationPermissions)
                        }
                    }
                    LocationMode.Centered -> {
                        locationMode = LocationMode.Oriented
                    }
                    LocationMode.Oriented -> {
                        locationMode = LocationMode.Idle
                    }
                }
            }

            HazardBackground()
            HazardMap(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                colorScheme = MaterialTheme.colorScheme,
                onMarkerSelected = { place ->
                    viewModel.setActivePlace(place.id, centerOnMap = true)
                    coroutineScope.launch { sheetState.expand() }
                },
                onViewportChanged = viewModel::updateViewport,
                mapEvents = viewModel.mapEvents
            )

            LocationOrientationButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 16.dp),
                mode = locationMode,
                hasLocationPermission = hasLocationPermission,
                isLocationAvailable = locationHeadingState.location != null,
                onClick = onGpsButtonClick
            )


            if (uiState.isLoading) {
                LoadingOverlay(modifier = Modifier.fillMaxSize())
            }

            uiState.errorMessage?.let { message ->
                ErrorOverlay(
                    message = message,
                    onRetry = viewModel::loadPlaces,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        webViewUrl?.let { url ->
            Box(modifier = Modifier.fillMaxSize()) {
                WebView(url)
                IconButton(
                    onClick = { webViewUrl = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close web view",
                        tint = TextPrimary
                    )
                }
            }
        }
    }
}

private fun checkLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun shouldRecentre(previous: GeoPoint?, current: GeoPoint): Boolean {
    val distance = previous?.let { distanceMeters(it, current) } ?: return true
    return distance >= LOCATION_RECENTER_THRESHOLD_METERS
}

private fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = from.latitude.toRadians()
    val lat2 = to.latitude.toRadians()
    val deltaLat = (to.latitude - from.latitude).toRadians()
    val deltaLon = (to.longitude - from.longitude).toRadians()

    val sinLat = sin(deltaLat / 2)
    val sinLon = sin(deltaLon / 2)
    val a = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

private fun Double.toRadians(): Double = this * (PI / 180.0)

private fun bearingDelta(from: Float, to: Float): Float {
    val diff = ((to - from + 540f) % 360f) - 180f
    return abs(diff)
}

private enum class LocationMode {
    Idle,
    Centered,
    Oriented,
}

private const val LOCATION_RECENTER_THRESHOLD_METERS = 8.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val LOCATION_FOCUS_ZOOM = 17.5
private const val ORIENTATION_FOCUS_ZOOM = 18.0
private const val ORIENTATION_MIN_DELTA_DEGREES = 2f

@Composable
private fun hazardGridViewModel(): HazardGridViewModel {
    val context = LocalContext.current.applicationContext
    val repository = remember { PlacesRepository(context) }
    return viewModel(factory = HazardGridViewModelFactory(repository))
}

@Composable
private fun HazardPeninsulaSheet(
    uiState: HazardUiState,
    isCompact: Boolean,
    isExpanded: Boolean,
    onSearchChange: (String) -> Unit,
    onFloorsChange: (com.abandonsearch.hazardgrid.domain.FloorsFilter) -> Unit,
    onSecurityChange: (com.abandonsearch.hazardgrid.domain.ScaleFilter) -> Unit,
    onInteriorChange: (com.abandonsearch.hazardgrid.domain.ScaleFilter) -> Unit,
    onAgeChange: (com.abandonsearch.hazardgrid.domain.AgeFilter) -> Unit,
    onRatingChange: (com.abandonsearch.hazardgrid.domain.RatingFilter) -> Unit,
    onSortChange: (com.abandonsearch.hazardgrid.domain.SortOption) -> Unit,
    onClearFilters: () -> Unit,
    onResultSelected: (Int) -> Unit,
    onToggleExpand: () -> Unit,
    onOpenIntel: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        HazardSheetHeader(
            uiState = uiState,
            isExpanded = isExpanded,
            onClearFilters = onClearFilters,
            onToggleExpand = onToggleExpand
        )
        if (uiState.activePlace != null) {
            PlaceDetailCard(
                place = uiState.activePlace,
                onClose = onClose,
                onOpenIntel = onOpenIntel
            )
        } else {
            FilterPanel(
                uiState = uiState,
                isCompact = isCompact,
                onSearchChange = onSearchChange,
                onFloorsChange = onFloorsChange,
                onSecurityChange = onSecurityChange,
                onInteriorChange = onInteriorChange,
                onAgeChange = onAgeChange,
                onRatingChange = onRatingChange,
                onSortChange = onSortChange,
                onResultSelected = onResultSelected,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun HazardSheetHeader(
    uiState: HazardUiState,
    isExpanded: Boolean,
    onClearFilters: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = NightOverlay.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, SurfaceBorder),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HazardPulseIndicator()
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = AccentPrimary
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Radiation feed",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    val filteredCount = uiState.searchResults.size
                    val total = uiState.totalValid
                    val countText = if (total > 0) "$filteredCount / $total" else filteredCount.toString()
                    Text(
                        text = "Signals online: $countText",
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (uiState.hasFilters) {
            TextButton(onClick = onClearFilters) {
                Text(
                    text = "Reset filters",
                    color = AccentPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (isExpanded) "Collapse sheet" else "Expand sheet",
                tint = AccentPrimary
            )
        }
    }
}

@Composable
private fun HazardSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(AccentPrimary.copy(alpha = 0.75f))
        )
    }
}

@Composable
private fun LocationOrientationButton(
    modifier: Modifier = Modifier,
    mode: LocationMode,
    hasLocationPermission: Boolean,
    isLocationAvailable: Boolean,
    onClick: () -> Unit,
) {
    val icon = when (mode) {
        LocationMode.Idle -> Icons.Rounded.MyLocation
        LocationMode.Centered -> Icons.Rounded.GpsFixed
        LocationMode.Oriented -> Icons.Rounded.Navigation
    }
    val backgroundAlpha = when (mode) {
        LocationMode.Idle -> 0.85f
        LocationMode.Centered -> 0.9f
        LocationMode.Oriented -> 0.95f
    }
    val contentDescription = when {
        !hasLocationPermission -> "Enable location access"
        mode == LocationMode.Idle -> "Center map on my position"
        mode == LocationMode.Centered -> "Rotate map to match my direction"
        else -> "Disable compass mode"
    }
    val iconAlpha = if (!isLocationAvailable && hasLocationPermission && mode != LocationMode.Idle) 0.6f else 1f
    val iconTint = if (mode == LocationMode.Oriented) AccentPrimary else Color.White

    Surface(
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = NightOverlay.copy(alpha = backgroundAlpha),
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint.copy(alpha = iconAlpha)
            )
        }
    }
}


@Composable
private fun HazardPulseIndicator() {
    val transition = rememberInfiniteTransition(label = "hazard-pulse")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AccentPrimary.copy(alpha = glowAlpha * 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(AccentPrimary.copy(alpha = glowAlpha))
        )
    }
}

@Composable
private fun HazardBackground() {
    Box(modifier = Modifier.fillMaxSize().background(NightBackground)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 72.dp.toPx()
            val color = TextMuted.copy(alpha = 0.1f)
            val strokeWidth = 1.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = color,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = strokeWidth
                )
                x += gridSpacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = color,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokeWidth
                )
                y += gridSpacing
            }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x33F5C400), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.1f),
                    radius = size.minDimension
                ),
                size = Size(size.width, size.height)
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x33FF5050), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.12f),
                    radius = size.minDimension * 0.8f
                ),
                size = Size(size.width, size.height)
            )
        }
    }
}
