package com.abandonsearch.hazardgrid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.abandonsearch.hazardgrid.R
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.ui.theme.AccentPrimary
import com.abandonsearch.hazardgrid.ui.theme.NightOverlay
import com.abandonsearch.hazardgrid.ui.theme.SurfaceBorder
import com.abandonsearch.hazardgrid.ui.theme.TextMuted
import com.abandonsearch.hazardgrid.ui.theme.TextPrimary

@Composable
fun PlaceDetailCard(
    place: Place,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClose: () -> Unit,
    onOpenIntel: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = NightOverlay.copy(alpha = 0.96f),
        tonalElevation = 12.dp,
        shadowElevation = 18.dp,
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = place.title.ifBlank { "Unknown site" },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    if (place.address.isNotBlank()) {
                        Text(
                            text = place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
                IconButton(onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.hazard_close),
                        contentDescription = "Close detail",
                        tint = TextMuted
                    )
                }
            }
            if (place.description.isNotBlank()) {
                Text(
                    text = place.description.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            PlaceMetrics(place)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val coords = formatCoordinates(place)
                Text(
                    text = coords ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        coords?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
                ) {
                    Text("Copy")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val mapsUrl = buildMapsUrl(place)
                if (mapsUrl != null) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
                    ) {
                        Text("Open maps", fontWeight = FontWeight.SemiBold)
                    }
                }
                val url = place.url
                if (url.isNotBlank()) {
                    TextButton(
                        onClick = { onOpenIntel(url) },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
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
