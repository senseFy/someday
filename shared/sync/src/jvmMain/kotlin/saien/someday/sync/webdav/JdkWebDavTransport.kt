package saien.someday.sync.webdav

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JdkWebDavTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : WebDavTransport {
    override fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse {
        require(request.body == null || request.body.size <= request.maxRequestBodyBytes) {
            "WebDAV request exceeds the configured body limit."
        }
        val uri = URI.create("${configuration.normalizedEndpoint}/${request.path.trimStart('/')}")
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val bodyPublisher = request.body?.let(HttpRequest.BodyPublishers::ofByteArray)
            ?: HttpRequest.BodyPublishers.noBody()
        val httpRequest = builder.method(request.method, bodyPublisher).build()
        val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        val declaredLength = response.headers().firstValue("Content-Length")
        val parsedLength = declaredLength.orElse(null)?.toLongOrNull()
        require(declaredLength.isEmpty || parsedLength != null) {
            response.body().close()
            "WebDAV response has an invalid Content-Length."
        }
        require(parsedLength == null || parsedLength in 0..request.maxResponseBodyBytes.toLong()) {
            response.body().close()
            "WebDAV response exceeds the configured body limit."
        }
        return WebDavResponse(
            status = response.statusCode(),
            headers = response.headers().map().mapValues { (_, values) -> values.lastOrNull().orEmpty() },
            body = response.body().use { it.readBounded(request.maxResponseBodyBytes) },
        )
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() <= maxBytes - read) {
                "WebDAV response exceeds the configured body limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
