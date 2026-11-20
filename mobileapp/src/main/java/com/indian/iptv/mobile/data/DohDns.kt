package com.indian.iptv.mobile.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Fresh, minimal DNS-over-HTTPS fallback resolver (Cloudflare + Google JSON
 * APIs, both reachable by raw IP so TLS SNI-independent bootstrap works).
 *
 * Used because some ISPs poison the system resolver — e.g. returning
 * 0.0.0.0 for `iptv-org.github.io`, which is the default playlist host.
 * Normal lookups go through the system first; DoH is only a fallback.
 */
object DohDns : Dns {

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val aRecordRegex = Regex(""""data"\s*:\s*"((?:\d{1,3}\.){3}\d{1,3})"""")

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            viaDoh(hostname) ?: throw e
        }
    }

    private fun viaDoh(hostname: String): List<InetAddress>? {
        for (endpoint in listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")) {
            try {
                val request = Request.Builder()
                    .url("$endpoint?name=$hostname&type=A")
                    .header("accept", "application/dns-json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val addresses = aRecordRegex.findAll(body)
                        .map { InetAddress.getByName(it.groupValues[1]) }
                        .filter { !it.isSiteLocalAddress && !it.isLoopbackAddress }
                        .toList()
                    if (addresses.isNotEmpty()) return addresses
                }
            } catch (_: Exception) {
                // Try the next endpoint; if all fail, let the caller's
                // UnknownHostException propagate.
            }
        }
        return null
    }
}
