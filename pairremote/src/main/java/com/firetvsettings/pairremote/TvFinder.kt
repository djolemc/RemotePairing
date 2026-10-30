package com.firetvsettings.pairremote

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds devices on the local /24 network that accept connections on the ADB port.
 *
 * The ADB probe only opens and closes a TCP connection: it never authenticates, so it doesn't
 * trigger an "Allow debugging?" prompt on any device.
 */
object TvFinder {

    data class Found(val address: String, val name: String?)

    private const val TAG = "VolumeBridge"
    private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
    private const val PROBE_TIMEOUT_MS = 1_000
    private const val NAME_TIMEOUT_MS = 1_500
    private const val CAST_PORT = 8008
    private const val THREADS = 64
    // The first SYN to each address also waits for ARP; on a busy scan some replies come too late.
    private const val PASSES = 2

    /** Blocking; call it off the main thread. Devices with a known name (TVs, TV boxes) come first. */
    fun scan(): List<Found> {
        val own = localIpv4() ?: return emptyList()
        val prefix = own.substringBeforeLast('.')
        // The Fire TV itself listens on 5555 when ADB debugging is on.
        var remaining = (1..254).map { "$prefix.$it" }.filter { it != own }
        val open = mutableListOf<String>()
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            repeat(PASSES) {
                val probes = remaining.associateWith { host -> pool.submit<Boolean> { isPortOpen(host, TvAddress.DEFAULT_PORT) } }
                open += probes.filterValues { it.get() }.keys
                remaining = remaining - open.toSet()
            }
            val named = open.map { host -> pool.submit<Found> { Found(host, deviceName(host)) } }.map { it.get() }
            return named.sortedWith(compareBy({ it.name == null }, { it.address.substringAfterLast('.').toInt() }))
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    fun localIpv4(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** The Google Cast name ("Smart TV Pro"), else the router's host name, else null. */
    private fun deviceName(host: String): String? = castName(host)
        ?: runCatching { InetAddress.getByName(host).canonicalHostName }.getOrNull()?.takeIf { it != host }

    /**
     * Google TV and Android TV devices with Chromecast built in report their name on port 8008.
     * Plain HTTP over a socket: HttpURLConnection would need a cleartext network security exception.
     */
    private fun castName(host: String): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, CAST_PORT), NAME_TIMEOUT_MS)
            socket.soTimeout = NAME_TIMEOUT_MS
            socket.getOutputStream().write(
                // The cast server ignores HTTP/1.0 requests.
                "GET /setup/eureka_info?params=name HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()
            )
            val response = readHttpResponse(socket.getInputStream())
            JSONObject(response.substringAfter("\r\n\r\n")).optString("name").takeIf { it.isNotBlank() }
        }
    }.onFailure { Log.i(TAG, "No cast name from $host: $it") }.getOrNull()

    /** Reads headers plus Content-Length bytes: the cast server keeps the connection open regardless. */
    private fun readHttpResponse(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            val text = buffer.toString("UTF-8")
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            val length = CONTENT_LENGTH.find(text.substring(0, headerEnd))?.groupValues?.get(1)?.toIntOrNull()
            if (length == null || buffer.size() >= headerEnd + 4 + length) break
        }
        return buffer.toString("UTF-8")
    }
}
