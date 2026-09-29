package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.DataError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** JSON settings for PostgREST responses. New server columns do not break older app versions. */
internal val remoteJson = Json { ignoreUnknownKeys = true }

/** Decodes a JSON array of rows. */
internal fun <T> decodeRows(body: String, serializer: KSerializer<T>): List<T> =
    remoteJson.decodeFromString(ListSerializer(serializer), body)

/**
 * Decodes a single row. PostgREST returns an object for functions that return one row and an
 * array for functions that return a table, so both shapes are accepted.
 */
internal fun <T> decodeRow(body: String, serializer: KSerializer<T>): T {
    val element = remoteJson.parseToJsonElement(body)
    val row = if (element is JsonArray) element.firstOrNull() ?: throw DataError.NotFound() else element
    return remoteJson.decodeFromJsonElement(serializer, row)
}

/** Counts the rows in a JSON array response. */
internal fun countRows(body: String): Int =
    (remoteJson.parseToJsonElement(body) as? JsonArray)?.size ?: 0
