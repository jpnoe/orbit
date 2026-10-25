package com.orbit.wellness.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OpenFoodFactsProduct(
    val barcode: String,
    val name: String,
    val caloriesPer100g: Double?,
    val proteinPer100g: Double?,
    val fatPer100g: Double?,
    val carbsPer100g: Double?,
    /** Grams in one labeled serving; 100g when the catalog omits a serving size. */
    val servingGrams: Double,
    val servingSizeLabel: String,
)

class OpenFoodFactsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchProduct(barcode: String): Result<OpenFoodFactsProduct> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
                .header("User-Agent", "OrbitWellness/1.0 (local-first wellness app)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Open Food Facts error: HTTP ${response.code}"),
                    )
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                if (root.optInt("status", 0) != 1) {
                    return@withContext Result.failure(
                        IllegalStateException("Product not found for barcode $barcode"),
                    )
                }
                val product = root.getJSONObject("product")
                Result.success(product.toOpenFoodFactsProduct(fallbackBarcode = barcode))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun searchProducts(
        query: String,
        pageSize: Int = 12,
    ): Result<List<OpenFoodFactsProduct>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a food name to search."))
        }
        try {
            val url = "https://world.openfoodfacts.org/cgi/search.pl".toHttpUrl().newBuilder()
                .addQueryParameter("search_terms", trimmed)
                .addQueryParameter("search_simple", "1")
                .addQueryParameter("action", "process")
                .addQueryParameter("json", "1")
                .addQueryParameter("page_size", pageSize.toString())
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OrbitWellness/1.0 (local-first wellness app)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Open Food Facts error: HTTP ${response.code}"),
                    )
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val products = root.optJSONArray("products") ?: return@withContext Result.success(emptyList())
                val parsed = buildList {
                    for (i in 0 until products.length()) {
                        val product = products.optJSONObject(i) ?: continue
                        val mapped = product.toOpenFoodFactsProduct(fallbackBarcode = "")
                        if (mapped.name.isNotBlank() && mapped.name != "Unknown food") {
                            add(mapped)
                        }
                    }
                }
                Result.success(parsed)
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun JSONObject.toOpenFoodFactsProduct(fallbackBarcode: String): OpenFoodFactsProduct {
        val nutriments = optJSONObject("nutriments")
        val name = optString("product_name")
            .ifBlank { optString("generic_name") }
            .ifBlank { optString("product_name_en") }
            .ifBlank { "Unknown food" }
        val code = optString("code")
            .ifBlank { optString("_id") }
            .ifBlank { fallbackBarcode }
        val servingSizeLabel = optString("serving_size").trim().takeIf { it.isNotBlank() }
        val servingGrams = servingQuantityGrams()
            ?: parseServingSizeToGrams(servingSizeLabel)
            ?: 100.0
        return OpenFoodFactsProduct(
            barcode = code,
            name = name,
            caloriesPer100g = nutriments.doubleOrNull("energy-kcal_100g")
                ?: nutriments.doubleOrNull("energy-kcal"),
            proteinPer100g = nutriments.doubleOrNull("proteins_100g"),
            fatPer100g = nutriments.doubleOrNull("fat_100g"),
            carbsPer100g = nutriments.doubleOrNull("carbohydrates_100g"),
            servingGrams = servingGrams,
            servingSizeLabel = servingSizeLabel ?: "${formatGrams(servingGrams)} g",
        )
    }

    private fun JSONObject.servingQuantityGrams(): Double? {
        doubleOrNull("serving_quantity")?.takeIf { it > 0 }?.let { return it }
        optJSONObject("nutriments").doubleOrNull("serving-quantity")?.takeIf { it > 0 }?.let { return it }
        optJSONObject("nutriments").doubleOrNull("serving_quantity")?.takeIf { it > 0 }?.let { return it }
        return null
    }

    private fun parseServingSizeToGrams(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.lowercase()
        val number = Regex("""(\d+(?:[.,]\d+)?)""").find(lower)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        return when {
            "fl oz" in lower || "fl. oz" in lower || "floz" in lower -> number * 29.5735
            Regex("""\boz\b""").containsMatchIn(lower) -> number * 28.3495
            "ml" in lower || "millilitre" in lower || "milliliter" in lower -> number
            "kg" in lower -> number * 1000.0
            "lb" in lower || "pound" in lower -> number * 453.592
            "g" in lower || "gram" in lower -> number
            else -> number
        }
    }

    private fun formatGrams(value: Double): String {
        val rounded = (value * 10.0).toLong() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format("%.1f", rounded)
        }
    }

    private fun JSONObject?.doubleOrNull(key: String): Double? {
        if (this == null || !has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }
}

fun scaleMacrosPer100g(valuePer100g: Double?, weightGrams: Double): Double? {
    if (valuePer100g == null) return null
    return valuePer100g * (weightGrams / 100.0)
}
