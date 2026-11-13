package com.indian.iptv.data.remote

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS resolver that falls back to DNS-over-HTTPS when the system resolver is poisoned.
 *
 * Many ISPs and ad-blocking resolvers (AdGuard Home, Pi-hole, NextDNS, some carrier
 * filters) answer blocked domains with `0.0.0.0`. The JVM then happily tries to connect
 * to `host/0.0.0.0:443`, which surfaces as:
 *
 *   Failed to connect to iptv-org.github.io/0.0.0.0:443
 *
 * That looks like "the app has no internet", but it is purely a name-resolution problem.
 * Here we detect the junk answer and re-resolve over HTTPS against Cloudflare, then
 * Google — both reachable by IP literal, so they still work when ordinary DNS is filtered.
 *
 * The system resolver is always tried first, so on a healthy network this class adds no
 * latency and changes nothing.
 */
object DohDns : Dns {

    private const val TAG = "DohDns"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /** Cloudflare then Google. Both answer `application/dns-json` on the same shape. */
    private val endpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    private class Cached(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Deliberately has no custom [Dns] — using this would recurse into ourselves. */
    private val dohClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ipv4Literal = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

    override fun lookup(hostname: String): List<InetAddress> {
        // A literal IP needs no resolution at all.
        if (ipv4Literal.matches(hostname)) {
            return listOf(InetAddress.getByName(hostname))
        }

        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        if (system.isUsable()) return system

        cache[hostname]?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis()) return cached.addresses
        }

        for (endpoint in endpoints) {
            val resolved = runCatching { query(endpoint, hostname) }.getOrNull().orEmpty()
            if (resolved.isUsable()) {
                cache[hostname] = Cached(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
                Log.i(TAG, "resolved $hostname via $endpoint -> ${resolved.joinToString()}")
                return resolved
            }
            Log.w(TAG, "DoH lookup failed for $hostname via $endpoint")
        }

        throw UnknownHostException(
            "Could not resolve $hostname (system DNS returned ${system.joinToString().ifEmpty { "nothing" }})",
        )
    }

    private fun List<InetAddress>.isUsable(): Boolean =
        isNotEmpty() && any { !it.isAnyLocalAddress && !it.isLoopbackAddress }

    private fun query(endpoint: String, hostname: String): List<InetAddress> {
        val url = "$endpoint?name=$hostname&type=A"
        dohClient.newCall(
            Request.Builder().url(url).header("accept", "application/dns-json").build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val answers = json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
                ?: return emptyList()
            return answers.mapNotNull { entry ->
                val obj = entry.jsonObject
                // type 1 = A. CNAMEs (5) carry no address.
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "1") return@mapNotNull null
                obj["data"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            }
        }
    }
}