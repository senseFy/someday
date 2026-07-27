package saien.someday.sync.webdav

import saien.someday.sync.readBoundedResponseBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.runBlocking

class AndroidWebDavTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    },
) : WebDavTransport {
    override fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse =
        runBlocking {
            require(request.body == null || request.body.size <= request.maxRequestBodyBytes) {
                "WebDAV request exceeds the configured body limit."
            }
            val contentType = request.headers["Content-Type"]
                ?.let(ContentType::parse)
                ?: ContentType.Application.OctetStream
            val response = client.request("${configuration.normalizedEndpoint}/${request.path.trimStart('/')}") {
                method = HttpMethod.parse(request.method)
                request.headers.forEach { (name, value) -> header(name, value) }
                request.body?.let { body ->
                    setBody(ByteArrayContent(body, contentType))
                }
            }
            WebDavResponse(
                status = response.status.value,
                headers = response.headers.entries().associate { entry ->
                    entry.key to entry.value.lastOrNull().orEmpty()
                },
                body = response.readBoundedResponseBytes(request.maxResponseBodyBytes, "WebDAV"),
            )
        }
}
