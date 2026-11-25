package com.indian.iptv.addonserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Minimal Stremio-compatible HTTP server aggregating the configured add-ons. */
internal class StremioServer(private val context: Context) {

    @Volatile private var running = false
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val aggregator = Aggregator()

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            socket = server
            Thread {
                while (running && !server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { serve(client) } }
                }
            }.apply { isDaemon = true; name = "addon-server-accept" }.start()
        } catch (e: Exception) {
            running = false
            throw e
        }
    }

    fun refresh(context: Context) {
        aggregator.data(context, force = true)
    }

    fun repos(context: Context): List<RepoReader.RepoInfo> = aggregator.data(context).repos

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val request = reader.readLine() ?: return
        val rawPath = request.split(' ').getOrNull(1).orEmpty()
        val path = Net.decode(rawPath.substringBefore('?'))
        val (status, body) = handle(path)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
        client.close()
    }

    private fun handle(path: String): Pair<String, String> {
        val data = aggregator.data(context)
        aggregator.configure(context.applicationContext, data.upstreams)
        return when {
            path == "/manifest.json" || path == "/" -> "200 OK" to manifest(data).toString()
            path.startsWith("/catalog/") -> "200 OK" to catalog(data, path).toString()
            path.startsWith("/stream/") -> "200 OK" to streams(path).toString()
            else -> "404 Not Found" to JSONObject().put("error", "not found").toString()
        }
    }

    private fun manifest(data: Aggregator.Data): JSONObject {
        val catalogs = JSONArray()
        data.labels.forEach { (id, info) ->
            catalogs.put(
                JSONObject()
                    .put("type", info.type)
                    .put("id", id)
                    .put("name", info.name),
            )
        }
        return JSONObject()
            .put("id", "com.indian.iptv.addonserver")
            .put("version", "1.0.0")
            .put("name", "CNC Add-on Server")
            .put("resources", JSONArray().put("catalog").put("stream"))
            .put("types", JSONArray().put("tv").put("movie"))
            .put("catalogs", catalogs)
    }

    private fun catalog(data: Aggregator.Data, path: String): JSONObject {
        val clean = path.removePrefix("/catalog/").removeSuffix(".json")
        val parts = clean.split('/')
        val id = Net.decode(parts.getOrNull(1).orEmpty())
        var query = parts.getOrNull(2).orEmpty()
        if (query.startsWith("search=", true)) query = Net.decode(query.removePrefix("search="))
        val source = data.catalogs[id] ?: when (id) {
            ALL_TV -> data.tv
            ALL_MOVIES -> data.movies
            else -> emptyList()
        }
        val metas = JSONArray()
        source.filter { query.isBlank() || it.name.contains(query, true) }.forEach { meta ->
            metas.put(
                JSONObject()
                    .put("id", meta.id)
                    .put("type", meta.type)
                    .put("name", meta.name)
                    .put("poster", meta.poster ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("metas", metas)
    }

    private fun streams(path: String): JSONObject {
        val clean = path.removePrefix("/stream/").removeSuffix(".json")
        val type = clean.substringBefore('/')
        val id = Net.decode(clean.substringAfter('/', ""))
        return JSONObject().put("streams", aggregator.streams(type, id))
    }

    companion object {
        const val PORT = 8090
        const val ALL_TV = "all_tv"
        const val ALL_MOVIES = "all_movie"
    }
}
