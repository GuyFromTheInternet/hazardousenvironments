package com.abandonsearch.hazardgrid.ui.components

import android.content.Intent
import android.net.Uri
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
import com.abandonsearch.hazardgrid.R
import com.abandonsearch.hazardgrid.data.Place
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
        shadowElevation = 18.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (place.address.isNotBlank()) {
                        Text(
                            text = place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                IconButton(onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.hazard_close),
                        contentDescription = "Close detail",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (place.description.isNotBlank()) {
                Text(
                    text = place.description.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            PlaceMetrics(place)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val coords = formatCoordinates(place)
                Text(
                    text = coords ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        coords?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
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
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Open maps", fontWeight = FontWeight.SemiBold)
                    }
                }
                val url = place.url
                if (url.isNotBlank()) {
                    TextButton(
                        onClick = { onOpenIntel(url) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
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
