package com.orbit.wellness.data.sharing

/**
 * Encodes pre-packed SMS chunks into [StatusRequest.generatedBody] so approve
 * sends the exact same segments without re-wrapping.
 */
object SmsChunkCodec {
    /** ASCII Record Separator — unlikely in status prose. */
    const val SEP: String = "\u001E"

    fun encode(chunks: List<String>): String =
        chunks.filter { it.isNotBlank() }.joinToString(SEP)

    fun decode(encoded: String): List<String> =
        encoded.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    fun forDisplay(encoded: String): String =
        decode(encoded).joinToString("\n\n")
}
