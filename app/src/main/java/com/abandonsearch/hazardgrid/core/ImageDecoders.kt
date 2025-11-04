package com.abandonsearch.hazardgrid.core

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.abandonsearch.hazardgrid.data.PlaceImage

fun decodeBase64Image(image: PlaceImage): ImageBitmap? = runCatching {
    val bytes = Base64.decode(image.data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
