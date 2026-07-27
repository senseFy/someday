package saien.someday.sync

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable

/** Reads even chunked responses without ever buffering beyond the caller's protocol limit. */
internal suspend fun HttpResponse.readBoundedResponseBytes(
    maxBytes: Int,
    protocolName: String,
): ByteArray {
    require(maxBytes > 0)
    val declaredValue = headers[HttpHeaders.ContentLength]
    val declaredLength = declaredValue?.toLongOrNull()
    require(declaredValue == null || declaredLength != null) {
        "$protocolName response has an invalid Content-Length."
    }
    require(declaredLength == null || declaredLength in 0..maxBytes.toLong()) {
        "$protocolName response exceeds the configured body limit."
    }

    val channel = bodyAsChannel()
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        require(total <= maxBytes - read) {
            "$protocolName response exceeds the configured body limit."
        }
        chunks += buffer.copyOf(read)
        total += read
    }

    val result = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}
