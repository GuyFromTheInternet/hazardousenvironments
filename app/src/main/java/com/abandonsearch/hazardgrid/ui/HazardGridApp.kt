package com.abandonsearch.hazardgrid.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abandonsearch.hazardgrid.data.settings.MapApp
import com.abandonsearch.hazardgrid.data.settings.SettingsRepository
import com.abandonsearch.hazardgrid.data.PlacesRepository
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.ui.components.ErrorOverlay
import com.abandonsearch.hazardgrid.ui.components.FilterPanel
import com.abandonsearch.hazardgrid.ui.components.LoadingOverlay
import com.abandonsearch.hazardgrid.ui.components.PlaceDetailCard
import com.abandonsearch.hazardgrid.ui.components.WebView
import com.abandonsearch.hazardgrid.ui.map.HazardMap
import com.abandonsearch.hazardgrid.ui.map.rememberLocationHeadingState
import com.abandonsearch.hazardgrid.ui.navigation.TRANSITION_DURATION
import com.abandonsearch.hazardgrid.ui.navigation.enterTransition
import com.abandonsearch.hazardgrid.ui.navigation.exitTransition
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import com.abandonsearch.hazardgrid.ui.settings.SettingsScreen
import com.abandonsearch.hazardgrid.ui.settings.SettingsViewModel
import com.abandonsearch.hazardgrid.ui.settings.SettingsViewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HazardGridApp() {
    val viewModel = hazardGridViewModel()
    val settingsViewModel = hazardSettingsViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isCompact = configuration.screenWidthDp < 900
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val lightBars = !showSettings
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val basePeekHeight = 140.dp + navBarHeight
    val maxPeekHeight = screenHeight * 0.75f
    val loweredPeekTarget = (basePeekHeight - 24.dp).coerceAtLeast(72.dp)
    val elevatedPeekTarget = (basePeekHeight + screenHeight * 0.2f).coerceAtMost(maxPeekHeight)
    val isSheetMoving by remember {
        derivedStateOf { sheetState.targetValue != sheetState.currentValue }
    }
    var isMapInteracting by remember { mutableStateOf(false) }
    var mapDragEndJob by remember { mutableStateOf<Job?>(null) }
    var sheetLowered by remember { mutableStateOf(false) }

    LaunchedEffect(isSheetMoving) {
        if (isSheetMoving) {
            sheetLowered = true
        } else {
            delay(200)
            sheetLowered = false
        }
    }

    val shouldLowerSheet = sheetLowered || isMapInteracting
    val peekHeight by animateDpAsState(
        targetValue = when {
            shouldLowerSheet -> loweredPeekTarget
            uiState.activePlace != null -> elevatedPeekTarget
            else -> basePeekHeight
        },
        animationSpec = tween(durationMillis = 140),
        label = "sheetPeek"
    )

    val handleMapPanChanged: (Boolean) -> Unit = { isDragging ->
        if (isDragging) {
            mapDragEndJob?.cancel()
            if (!isMapInteracting) {
                isMapInteracting = true
            }
        } else if (isMapInteracting) {
            mapDragEndJob?.cancel()
            mapDragEndJob = coroutineScope.launch {
                delay(1300)
                isMapInteracting = false
            }
        }
    }

    // Track sheet state
    val isSheetExpanded by remember {
        derivedStateOf {
            sheetState.currentValue == SheetValue.Expanded
        }
    }

    val sheetElevation = 14.dp
    val sheetBackgroundColor = MaterialTheme.colorScheme.surfaceColorAtElevation(sheetElevation)

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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result.entries.any { it.value }
    }

    val locationHeadingState = rememberLocationHeadingState(
        requestUpdates = hasLocationPermission,
        hasLocationPermission = hasLocationPermission,
    )

    val onGpsButtonClick: () -> Unit = {
        if (hasLocationPermission) {
            locationHeadingState.location?.let {
                viewModel.sendMapCommand(
                    HazardGridViewModel.MapCommand.FocusOnLocation(
                        location = it,
                        zoom = LOCATION_FOCUS_ZOOM,
                        animate = false
                    )
                )
            }
        } else {
            permissionLauncher.launch(locationPermissions)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetShape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            sheetDragHandle = { HazardSheetHandle() },
            sheetContainerColor = sheetBackgroundColor,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            sheetTonalElevation = sheetElevation,
            sheetSwipeEnabled = true,
            sheetContent = {
                BoxWithConstraints(modifier = Modifier.fillMaxHeight(0.75f)) {
                    HazardPeninsulaSheet(
                        uiState = uiState,
                        isCompact = isCompact,
                        isExpanded = isSheetExpanded,
                        mapApp = appSettings.defaultMapApp,
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
                        onClose = {
                            viewModel.setActivePlace(null, centerOnMap = false)
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                HazardBackground()
                HazardMap(
                    modifier = Modifier.fillMaxSize(),
                    uiState = uiState,
                    colorScheme = MaterialTheme.colorScheme,
                    primaryStyleUri = DEFAULT_PRIMARY_STYLE_URI,
                    fallbackStyleUri = DEFAULT_FALLBACK_STYLE_URI,
                    onMarkerSelected = { place ->
                        viewModel.setActivePlace(place?.id, centerOnMap = place != null)
                    },
                    onViewportChanged = viewModel::updateViewport,
                    onMapScrolled = {
                        if (uiState.activePlace == null) {
                            coroutineScope.launch {
                                sheetState.partialExpand()
                            }
                        }
                    },
                    onMapPanChanged = handleMapPanChanged,
                    mapEvents = viewModel.mapEvents,
                    mergeShapesEnabled = appSettings.mergeShapesEnabled,
                    userLocation = locationHeadingState.location,
                    userHeading = locationHeadingState.heading,
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
        }

        val buttonBottomPadding = with(density) {
            try {
                val offsetPx = sheetState.requireOffset()
                val screenHeightPx = screenHeight.toPx()
                (screenHeightPx - offsetPx).toDp() + 100.dp
            } catch (e: Exception) {
                peekHeight + 100.dp
            }
        }

        LocationButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = buttonBottomPadding),
            hasLocationPermission = hasLocationPermission,
            isLocationAvailable = locationHeadingState.location != null,
            onClick = onGpsButtonClick,
            backgroundColor = sheetBackgroundColor
        )

        AnimatedActionButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp),
            icon = Icons.Rounded.Settings,
            contentDescription = "Open settings",
            iconTint = MaterialTheme.colorScheme.primary,
            backgroundColor = sheetBackgroundColor,
            size = 64.dp,
            onClick = { showSettings = true }
        )

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
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(animationSpec = tween(durationMillis = TRANSITION_DURATION)),
            exit = fadeOut(animationSpec = tween(durationMillis = TRANSITION_DURATION)),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showSettings,
                    enter = enterTransition(),
                    exit = exitTransition()
                ) {
                    SettingsScreen(
                        settings = appSettings,
                        onDismiss = { showSettings = false },
                        onMapAppSelected = settingsViewModel::setDefaultMapApp,
                        onMergeShapesChanged = settingsViewModel::setMergeShapesEnabled
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

private const val LOCATION_RECENTER_THRESHOLD_METERS = 8.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val LOCATION_FOCUS_ZOOM = 17.5
private const val DEFAULT_PRIMARY_STYLE_URI =
    "https://api.maptiler.com/maps/019a87ef-2892-72c9-9aea-182dfb03ab3f/style.json?key=rZaJRfHisEbyyIB7N5JS "
private const val DEFAULT_FALLBACK_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

@Composable
private fun hazardGridViewModel(): HazardGridViewModel {
    val context = LocalContext.current.applicationContext
    val repository = remember { PlacesRepository(context) }
    return viewModel(factory = HazardGridViewModelFactory(repository))
}

@Composable
private fun hazardSettingsViewModel(): SettingsViewModel {
    val context = LocalContext.current.applicationContext
    val repository = remember { SettingsRepository(context) }
    return viewModel(factory = SettingsViewModelFactory(repository))
}

@Composable
private fun HazardPeninsulaSheet(
    uiState: HazardUiState,
    isCompact: Boolean,
    isExpanded: Boolean,
    mapApp: MapApp,
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
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (uiState.activePlace != null) {
            HazardSheetHeader(
                uiState = uiState,
                isExpanded = isExpanded,
                onClearFilters = onClearFilters,
                onToggleExpand = onToggleExpand
            )
            PlaceDetailCard(
                place = uiState.activePlace,
                mapApp = mapApp,
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
                mapApp = mapApp,
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
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Radiation feed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val filteredCount = uiState.searchResults.size
                    val total = uiState.totalValid
                    val countText = if (total > 0) "$filteredCount / $total" else filteredCount.toString()
                    Text(
                        text = "Signals online: $countText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (isExpanded) "Collapse sheet" else "Expand sheet",
                tint = MaterialTheme.colorScheme.primary
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
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LocationButton(
    modifier: Modifier = Modifier,
    hasLocationPermission: Boolean,
    isLocationAvailable: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color = MaterialTheme.colorScheme.surface
) {
    val contentDescription = if (hasLocationPermission) "Center map on my position" else "Enable location access"
    val iconAlpha = if (!isLocationAvailable && hasLocationPermission) 0.6f else 1f

    val shapes: List<RoundedPolygon> = remember {
        listOf(
            MaterialShapes.Circle,
            MaterialShapes.Diamond,
            MaterialShapes.Sunny,
            MaterialShapes.Square,
            MaterialShapes.Pill,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Slanted,
            MaterialShapes.Triangle,
            MaterialShapes.Pentagon,
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Gem,
            MaterialShapes.Cookie7Sided,
            MaterialShapes.Flower
        )
    }

    val randomSeed = remember { Random(SystemClock.uptimeMillis()) }
    fun pickShape(exclude: RoundedPolygon? = null): RoundedPolygon {
        val pool = if (exclude == null) shapes else shapes.filter { it != exclude }
        return pool[randomSeed.nextInt(pool.size)]
    }
    var currentShape by remember { mutableStateOf(pickShape()) }
    var targetShape by remember { mutableStateOf(pickShape(currentShape)) }
    val animationProgress = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .size(88.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                scope.launch {
                    if (animationProgress.isRunning) {
                        animationProgress.stop()
                        animationProgress.snapTo(1f)
                    }
                    currentShape = targetShape
                    targetShape = pickShape(currentShape)
                    animationProgress.snapTo(0f)
                    animationProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                    )
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shapeRadius = 36.dp.toPx()
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            val androidPath = android.graphics.Path()
            if (animationProgress.value in 0f..1f) {
                val morph = Morph(currentShape, targetShape)
                morph.toPath(animationProgress.value, androidPath)
            } else {
                targetShape.toPath(androidPath)
            }

            val bounds = android.graphics.RectF()
            androidPath.computeBounds(bounds, true)
            val matrix = android.graphics.Matrix()
            matrix.setRectToRect(
                bounds,
                android.graphics.RectF(-shapeRadius, -shapeRadius, shapeRadius, shapeRadius),
                android.graphics.Matrix.ScaleToFit.CENTER
            )
            androidPath.transform(matrix)

            val shapeOffsetY = when (targetShape) {
                MaterialShapes.Triangle -> -4.dp.toPx()
                MaterialShapes.Pentagon -> -2.dp.toPx()
                else -> 0f
            }

            androidPath.offset(centerX, centerY + shapeOffsetY)
            val composePath = androidPath.asComposePath()
            drawPath(path = composePath, color = backgroundColor)
        }

        Icon(
            imageVector = Icons.Rounded.MyLocation,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(36.dp)
                .alpha(iconAlpha)
        )
    }
}

@Composable
private fun AnimatedActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    contentDescription: String,
    iconTint: Color,
    backgroundColor: Color,
    iconAlpha: Float = 1f,
    size: Dp = 88.dp,
    onClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clickScale = remember { Animatable(1f) }
    val pulseTransition = rememberInfiniteTransition(label = "action-button-pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )
    val combinedScale = pulseScale * clickScale.value

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = combinedScale
                scaleY = combinedScale
            }
            .clip(CircleShape)
            .shadow(16.dp, CircleShape, clip = false)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                scope.launch {
                    clickScale.snapTo(0.9f)
                    clickScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier
                .size(36.dp)
                .alpha(iconAlpha)
        )
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
            .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha))
        )
    }
}

@Composable
private fun HazardBackground() {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 72.dp.toPx()
            val strokeWidth = 1.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = strokeWidth
                )
                x += gridSpacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokeWidth
                )
                y += gridSpacing
            }
        }
    }
}
