package com.abandonsearch.hazardgrid.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream

class PlacesRepository(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun loadPlaces(): List<Place> = withContext(Dispatchers.IO) {
        for (assetName in ASSET_CANDIDATES) {
            val result = runCatching {
                context.assets.open(assetName).use { stream ->
                    if (assetName.endsWith(".gz")) {
                        decodeCompressed(stream)
                    } else {
                        decodePlain(stream)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to load asset $assetName", error)
            }.getOrNull()
            if (result != null && result.isNotEmpty()) {
                Log.i(TAG, "Loaded ${result.size} places from $assetName")
                return@withContext result
            }
        }
        throw FileNotFoundException("No place dataset asset found. Checked $ASSET_CANDIDATES")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeCompressed(inputStream: InputStream): List<Place> =
        GZIPInputStream(BufferedInputStream(inputStream)).use(::decodeStream)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodePlain(inputStream: InputStream): List<Place> =
        BufferedInputStream(inputStream).use(::decodeStream)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeStream(stream: InputStream): List<Place> =
        json.decodeFromStream(ListSerializer(Place.serializer()), stream)

    companion object {
        private const val TAG = "PlacesRepository"
        private val ASSET_CANDIDATES = listOf(
            "places.json.gz",
            "place.json.gz",
            "data-enriched.json",
            "places.json",
            "data.json"
        )
    }
}
