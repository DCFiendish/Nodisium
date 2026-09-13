package net.aechronis.nodes

import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal fire-and-forget Discord webhook poster -- used for war start/end
 * notifications for now. No-ops silently if no webhook URL is configured
 * (e.g. local dev/test runs). Posts async so a slow/unreachable Discord
 * never stalls the calling thread.
 */
object DiscordWebhook {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun send(webhookUrl: String?, content: String) {
        if (webhookUrl.isNullOrBlank()) return

        val body = JsonObject().apply { addProperty("content", content) }.toString()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .exceptionally { err ->
                System.err.println("[Nodes] Discord webhook post failed: ${err.message}")
                null
            }
    }
}
