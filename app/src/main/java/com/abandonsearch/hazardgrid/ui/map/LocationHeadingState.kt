package com.abandonsearch.hazardgrid.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

data class LocationHeadingState(
    val location: GeoPoint?,
    val heading: Float?,
)

@Composable
fun rememberLocationHeadingState(
    requestUpdates: Boolean,
    hasLocationPermission: Boolean,
): LocationHeadingState {
    val context = LocalContext.current
    var location by remember { mutableStateOf<GeoPoint?>(null) }
    var heading by remember { mutableStateOf<Float?>(null) }

    val fusedClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }

    DisposableEffect(requestUpdates, hasLocationPermission, fusedClient) {
        if (!requestUpdates || !hasLocationPermission) {
            location = null
            return@DisposableEffect onDispose { }
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val latest = result.locations.lastOrNull() ?: return
                location = GeoPoint(latest.latitude, latest.longitude)
            }
        }

        @SuppressLint("MissingPermission")
        fun startUpdates() {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    location = GeoPoint(loc.latitude, loc.longitude)
                }
            }
        }

        try {
            startUpdates()
        } catch (se: SecurityException) {
            location = null
        }

        onDispose {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    val sensorManager = remember(context) { context.getSystemService<SensorManager>() }
    DisposableEffect(requestUpdates, sensorManager) {
        if (!requestUpdates || sensorManager == null) {
            heading = null
            return@DisposableEffect onDispose { }
        }

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            heading = null
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)
        val adjustedMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                remapForDisplay(context, rotationMatrix, adjustedMatrix)
                SensorManager.getOrientation(adjustedMatrix, orientationValues)
                val azimuthRad = orientationValues[0]
                val azimuthDeg = normalizeBearing(Math.toDegrees(azimuthRad.toDouble()).toFloat())
                heading = smoothBearing(heading, azimuthDeg)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return LocationHeadingState(location = location, heading = heading)
}

private fun remapForDisplay(
    context: Context,
    inputMatrix: FloatArray,
    outputMatrix: FloatArray,
) {
    val rotation = getDisplayRotation(context)
    val axisX: Int
    val axisY: Int
    when (rotation) {
        Surface.ROTATION_0 -> {
            axisX = SensorManager.AXIS_X
            axisY = SensorManager.AXIS_Y
        }
        Surface.ROTATION_90 -> {
            axisX = SensorManager.AXIS_Y
            axisY = SensorManager.AXIS_MINUS_X
        }
        Surface.ROTATION_180 -> {
            axisX = SensorManager.AXIS_MINUS_X
            axisY = SensorManager.AXIS_MINUS_Y
        }
        Surface.ROTATION_270 -> {
            axisX = SensorManager.AXIS_MINUS_Y
            axisY = SensorManager.AXIS_X
        }
        else -> {
            axisX = SensorManager.AXIS_X
            axisY = SensorManager.AXIS_Y
        }
    }
    SensorManager.remapCoordinateSystem(inputMatrix, axisX, axisY, outputMatrix)
}

private fun getDisplayRotation(context: Context): Int {
    val windowManager = ContextCompat.getSystemService(context, WindowManager::class.java)
    val display = windowManager?.defaultDisplay
    return display?.rotation ?: Surface.ROTATION_0
}

private fun normalizeBearing(bearing: Float): Float {
    var value = bearing % 360f
    if (value < 0f) value += 360f
    return value
}

private fun smoothBearing(
    previous: Float?,
    target: Float,
    alpha: Float = 0.2f,
): Float {
    val prev = previous ?: target
    val delta = shortestBearingDelta(prev, target)
    return normalizeBearing(prev + delta * alpha)
}

private fun shortestBearingDelta(from: Float, to: Float): Float {
    val diff = ((to - from + 540f) % 360f) - 180f
    return diff
}

private const val LOCATION_INTERVAL_MS = 5_000L
private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 1_500L
