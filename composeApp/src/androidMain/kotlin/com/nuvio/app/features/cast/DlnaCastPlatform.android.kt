package com.nuvio.app.features.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

internal actual object DlnaCastPlatform {
    private const val TAG = "DlnaCastPlatform"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_MX = 2

    private val SEARCH_TYPES = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all",
    )

    private var appContext: Context? = null

    /** The home network found by the last search; TV commands and the relay go over it. */
    @Volatile private var localNetwork: CastLocalNetwork? = null

    /** Talks to TVs over Wi-Fi directly, so a VPN on the phone can't swallow the commands. */
    @Volatile private var httpClient: OkHttpClient = CastLocalNetwork.baseClient

    /** Fetches what the relay passes on, the same way the phone's own player does (including through a VPN). */
    private val relayClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var relay: CastRelay? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun refreshLocalNetwork(): CastLocalNetwork? {
        val context = appContext ?: return null
        return CastLocalNetwork.find(context).also {
            localNetwork = it
            httpClient = it?.httpClient(CastLocalNetwork.baseClient) ?: CastLocalNetwork.baseClient
        }
    }

    /**
     * Finds TVs with SSDP: a search to the UPnP multicast address, sent over Wi-Fi even when a VPN is
     * on, and the same search sent straight to every address on the home network, for VPNs and routers
     * that don't pass multicast. Each device that answers is asked for its description.
     */
    actual suspend fun scanDevices(timeoutMs: Int): DlnaScanResult = withContext(Dispatchers.IO) {
        CastLog.add("Search started")
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val network = refreshLocalNetwork()
                ?: return@withContext DlnaScanResult.Failure("Connect to Wi-Fi to find TVs.")
            try {
                val wifi = appContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("nuvio_dlna_scan")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()
            } catch (e: Exception) {
                Log.w(TAG, "MulticastLock acquire failed: ${e.message}")
            }

            val locations = LinkedHashSet<String>()
            DatagramSocket().use { socket ->
                // If the socket can't be tied to Wi-Fi, the normal route may still reach the TV.
                runCatching { network.bind(socket) }
                var sent = 0
                fun trySend(st: String, address: InetAddress) {
                    val bytes = searchMessage(st, address.hostAddress ?: SSDP_ADDRESS)
                    if (runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, SSDP_PORT)) }.isSuccess) sent++
                }
                val group = InetAddress.getByName(SSDP_ADDRESS)
                // UDP can drop packets, so search twice; some TVs only answer searches for their device type.
                repeat(2) { for (st in SEARCH_TYPES) trySend(st, group) }
                network.neighbours().chunked(32).forEach { batch ->
                    for (address in batch) {
                        trySend(SEARCH_TYPES[0], address)
                        trySend(SEARCH_TYPES[1], address)
                    }
                    // A short pause between batches keeps the phone's Wi-Fi from dropping a burst.
                    Thread.sleep(5)
                }
                Log.i(TAG, "Sent $sent searches (Wi-Fi bound: ${network.canBind}, own address ${network.ownAddress?.hostAddress})")
                CastLog.add("Sent $sent searches (Wi-Fi bound: ${network.canBind}, VPN: ${appContext?.let { CastLocalNetwork.hasVpn(it) }})")
                if (sent == 0) {
                    return@withContext DlnaScanResult.Failure(blockedReason())
                }

                val buffer = ByteArray(8192)
                val deadline = System.currentTimeMillis() + timeoutMs
                // Short waits, so the search ends on time.
                socket.soTimeout = 250
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val location = parseLocationHeader(String(packet.data, 0, packet.length, Charsets.UTF_8))?.trim()
                    if (location != null && location.startsWith("http", ignoreCase = true) && locations.add(location)) {
                        Log.i(TAG, "Discovered LOCATION: $location from ${packet.address.hostAddress}")
                    }
                }
            }
            Log.i(TAG, "SSDP discovery finished, found ${locations.size} locations")

            // Fetch descriptions side by side, so a slow device doesn't hold up the others.
            val devices = coroutineScope {
                locations.map { location -> async { describe(location) } }.awaitAll().filterNotNull()
            }
            DlnaScanResult.Success(devices.distinctBy { it.id })
        } catch (e: Exception) {
            Log.e(TAG, "scanDevices error", e)
            DlnaScanResult.Failure(e.message ?: "Scan error")
        } finally {
            try { multicastLock?.release() } catch (_: Exception) {}
        }
    }

    private fun searchMessage(st: String, host: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $host:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $SSDP_MX\r\n")
        append("ST: $st\r\n")
        append("\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun blockedReason(): String {
        val vpn = appContext?.let { CastLocalNetwork.hasVpn(it) } == true
        return "Nuvio can't reach your home network." +
            if (vpn) " Allow local network access (LAN) in your VPN, or turn it off while casting." else ""
    }

    private fun describe(location: String): DlnaDevice? = try {
        httpClient.newCall(Request.Builder().url(location).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $location failed ${resp.code}")
                return@use null
            }
            val xml = resp.body.string()
            val parsed = DlnaSoap.parseDeviceDescription(xml, location) ?: run {
                // Not a renderer (no AVTransport), such as a router or media server.
                Log.d(TAG, "No AVTransport in $location")
                return@use null
            }
            DlnaDevice(
                id = parsed.udn,
                friendlyName = parsed.friendlyName,
                modelName = parsed.modelName,
                manufacturer = parsed.manufacturer,
                locationUrl = location,
                controlUrl = parsed.controlUrl,
                eventSubUrl = parsed.eventSubUrl,
                avTransportServiceType = parsed.serviceType,
                ipAddress = runCatching { URL(location).host }.getOrNull(),
                renderingControlUrl = parsed.renderingControlUrl,
            ).also { Log.i(TAG, "Parsed device: ${it.friendlyName} control=${it.controlUrl}") }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error fetching $location: ${e.message}")
        null
    }

    private fun parseLocationHeader(response: String): String? {
        val lines = response.split("\r\n", "\n")
        for (line in lines) {
            if (line.startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    /**
     * Gives the TV the original link when it can open it itself, which leaves the phone free.
     * Downloads, local torrent streams and links that need headers go through the relay on the phone
     * instead, since the TV can't open those.
     */
    actual suspend fun prepareProxyUrl(request: DlnaCastRequest): String = withContext(Dispatchers.IO) {
        val url = request.sourceUrl
        if (!canRelay(url)) return@withContext url
        if (isLocalSource(url) || request.sourceHeaders.isNotEmpty()) {
            relayVideo(request) ?: url
        } else {
            url
        }
    }

    actual suspend fun castToDevice(device: DlnaDevice, request: DlnaCastRequest, proxyUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = proxyUrl.ifBlank { request.sourceUrl }
        val viaRelay = relay?.isVideoUrl(url) == true
        if (sendToTv(device, request, url, viaRelay)) {
            if (viaRelay) keepRelayRunning(device) else if (relay?.hasSubtitle != true) stopRelayService()
            return@withContext true
        }
        if (viaRelay || !canRelay(request.sourceUrl)) return@withContext false
        // Often the TV can't open the link itself: a VPN only on the phone, a blocked server, redirects
        // or headers it doesn't send. The phone can, so it fetches the video for the TV.
        Log.i(TAG, "${device.friendlyName} couldn't open the link; relaying through the phone")
        val relayed = relayVideo(request) ?: return@withContext false
        sendToTv(device, request, relayed, viaRelay = true).also { if (it) keepRelayRunning(device) }
    }

    /**
     * Shows [request]'s subtitle (or none) on the TV. DLNA has no command to change subtitles, so the
     * video is sent again with the new subtitle and picks up at [positionMs], the way other DLNA apps do it.
     */
    actual suspend fun changeSubtitle(device: DlnaDevice, request: DlnaCastRequest, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        val url = lastVideoUrl ?: return@withContext false
        val ok = sendToTv(device, request.copy(startPositionMs = positionMs), url, viaRelay = relay?.isVideoUrl(url) == true)
        if (ok && relay?.hasSubtitle == true) keepRelayRunning(device)
        ok
    }

    @Volatile private var lastVideoUrl: String? = null

    private fun sendToTv(device: DlnaDevice, request: DlnaCastRequest, url: String, viaRelay: Boolean): Boolean = commandLock.withLockBlocking {
        lastVideoUrl = url
        val mime = (if (viaRelay) relay?.videoMimeType else null) ?: request.mimeType.ifBlank { "video/mp4" }
        val subtitleUrl = request.subtitleUrl?.let { relaySubtitle(it, request.subtitleHeaders) }
        if (request.subtitleUrl == null) relay?.clearSubtitle()
        val didl = DlnaSoap.buildDidlMetadata(
            title = request.title.ifBlank { "Nuvio Cast" },
            proxyUrl = url,
            mimeType = mime,
            duration = request.durationMs?.let { DlnaSoap.formatDurationMs(it) },
            subtitleUrl = subtitleUrl,
        )
        val setUriBody = DlnaSoap.buildSetAvTransportUriBody(uri = url, metadata = didl)
        Log.i(TAG, "SetAVTransportURI to ${device.controlUrl} uri=$url relay=$viaRelay subtitle=$subtitleUrl")
        CastLog.add(
            "Send to ${device.friendlyName} (${device.manufacturer ?: "?"} ${device.modelName ?: ""}): " +
                "${if (viaRelay) "relay" else "direct"} ${CastLog.shortUrl(request.sourceUrl)} as $mime, " +
                "subtitle ${request.subtitleUrl?.let(CastLog::shortUrl) ?: "none"}, from ${request.startPositionMs / 1000}s",
        )
        if (!soap(device, "SetAVTransportURI", setUriBody)) {
            // Some TVs refuse a new link while they're still playing; stopping first lets them take it.
            soap(device, "Stop", DlnaSoap.buildStopBody())
            if (!soap(device, "SetAVTransportURI", setUriBody)) return@withLockBlocking false
        }
        // TVs such as LG's start playing as soon as they get the link, then refuse Play or hold its
        // reply until they've buffered. What counts is whether the TV ends up playing.
        val played = soap(device, "Play", DlnaSoap.buildPlayBody(), slowClient)
        if (!waitUntilPlaying(device, if (played) 8 else 5, acceptStarting = !played)) {
            if (!played) {
                Log.w(TAG, "${device.friendlyName} didn't start playing")
                return@withLockBlocking false
            }
        }
        if (request.startPositionMs > 5_000) {
            // Pick up where the phone was; TVs only accept a seek once playback has started.
            soap(device, "Seek", DlnaSoap.buildSeekBody(target = DlnaSoap.formatDurationMs(request.startPositionMs)))
        }
        true
    }

    /** Waits (about a second per try) until the TV reports it's playing; false if it never does. */
    private fun waitUntilPlaying(device: DlnaDevice, tries: Int, acceptStarting: Boolean = false): Boolean {
        repeat(tries) { attempt ->
            val state = soapResponse(device, "GetTransportInfo", DlnaSoap.buildGetTransportInfoBody())
                ?.let(DlnaSoap::parseTransportState)
            Log.d(TAG, "Transport state: $state")
            CastLog.add("TV state: $state")
            when (state) {
                "PLAYING", "PAUSED_PLAYBACK" -> return true
                "NO_MEDIA_PRESENT" -> return false
                "TRANSITIONING" -> if (acceptStarting && attempt == tries - 1) return true
            }
            if (attempt < tries - 1) try { Thread.sleep(if (attempt == 0) 300 else 800) } catch (_: Exception) {}
        }
        return false
    }

    /**
     * One request to the TV at a time: TVs answer slowly and some mishandle overlapping requests.
     * Commands wait their turn; background checks skip when a command is under way.
     */
    private val commandLock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLockBlocking(block: () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    private inline fun <T> java.util.concurrent.locks.ReentrantLock.ifFree(block: () -> T?): T? {
        if (!tryLock()) return null
        try { return block() } finally { unlock() }
    }

    /** For Play, which some TVs only answer once the video has started. */
    private val slowClient: OkHttpClient
        get() = httpClient.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

    private fun soapRequest(url: String, serviceType: String, action: String, body: String): Request =
        Request.Builder()
            .url(url)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .build()

    private fun soapResponse(device: DlnaDevice, action: String, body: String): String? = try {
        httpClient.newCall(soapRequest(device.controlUrl, device.avTransportServiceType, action, body)).execute()
            .use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) {
                    text
                } else {
                    CastLog.add("TV $action -> ${resp.code} (${DlnaSoap.describeFault(text) ?: text.take(120)})")
                    null
                }
            }
    } catch (e: Exception) {
        Log.w(TAG, "$action failed: ${e.message}")
        CastLog.add("TV $action failed: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun soap(
        device: DlnaDevice,
        action: String,
        body: String,
        client: OkHttpClient = httpClient,
        url: String = device.controlUrl,
        serviceType: String = device.avTransportServiceType,
    ): Boolean = try {
        val started = System.currentTimeMillis()
        client.newCall(soapRequest(url, serviceType, action, body)).execute().use { resp ->
            val text = resp.body.string()
            val took = System.currentTimeMillis() - started
            Log.i(TAG, "$action response ${resp.code}${if (resp.isSuccessful) "" else " body=${text.take(300)}"}")
            CastLog.add(
                "TV $action -> ${resp.code} in ${took}ms" +
                    if (resp.isSuccessful) "" else " (${DlnaSoap.describeFault(text) ?: text.take(120)})",
            )
            resp.isSuccessful
        }
    } catch (e: Exception) {
        Log.w(TAG, "$action failed: ${e.message}")
        CastLog.add("TV $action failed: ${e.javaClass.simpleName} ${e.message}")
        false
    }

    private fun ensureRelay(): CastRelay? {
        relay?.let { return it }
        val network = localNetwork ?: refreshLocalNetwork()
        val address = network?.ownAddress ?: return null
        return try {
            CastRelay(relayClient, address).also { relay = it }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the relay", e)
            null
        }
    }

    /** Starts (or reuses) the relay for [request]'s video and returns the address for the TV, or null without Wi-Fi. */
    private fun relayVideo(request: DlnaCastRequest): String? {
        val relay = ensureRelay() ?: return null
        val url = request.sourceUrl
        val source = localFile(url)?.let { file ->
            CastRelay.Source.LocalFile(file, CastRelay.mimeTypeFor(file.name))
        } ?: CastRelay.Source.Remote(
            url = url,
            headers = request.sourceHeaders,
            mimeType = request.mimeType.takeIf { it.startsWith("video/") && it != "video/mp4" } ?: CastRelay.mimeTypeFor(url),
        )
        return relay.serveVideo(source).also { Log.i(TAG, "Relaying ${url.take(80)} as $it (${source.mimeType})") }
    }

    /** Serves a subtitle (a downloaded file or an online one) to the TV as SRT, from the phone. */
    private fun relaySubtitle(url: String, headers: Map<String, String>): String? {
        val relay = ensureRelay() ?: return null
        val source = localFile(url)?.let { CastRelay.SubtitleSource.LocalFile(it) }
            ?: CastRelay.SubtitleSource.Remote(url, headers)
        return relay.serveSubtitle(source)
    }

    private fun keepRelayRunning(device: DlnaDevice) {
        appContext?.let { CastRelayService.start(it, device.friendlyName) }
    }

    private fun stopRelayService() {
        appContext?.let { CastRelayService.stop(it) }
    }

    /** HLS and DASH point the TV at many files, which a simple relay can't pass on. */
    private fun canRelay(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    private fun isLocalSource(url: String): Boolean {
        if (localFile(url) != null) return true
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    private fun localFile(url: String): File? {
        val file = when {
            url.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(url)) }.getOrNull()
            url.startsWith("/") -> File(url)
            else -> null
        }
        return file?.takeIf { it.isFile && it.canRead() }
    }

    actual suspend fun stopPlayback(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        commandLock.withLockBlocking { soap(device, "Stop", DlnaSoap.buildStopBody()) }
    }

    actual suspend fun pausePlayback(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        commandLock.withLockBlocking { soap(device, "Pause", DlnaSoap.buildPauseBody()) }
    }

    actual suspend fun resumePlayback(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        commandLock.withLockBlocking {
            // Some TVs only answer Play once playback has restarted.
            soap(device, "Play", DlnaSoap.buildPlayBody(), slowClient)
        }
    }

    /** Null when the TV is busy with a command, so position checks never hold up the controls. */
    actual suspend fun getPositionInfo(device: DlnaDevice): Long? = withContext(Dispatchers.IO) {
        commandLock.ifFree {
            soapResponse(device, "GetPositionInfo", DlnaSoap.buildGetPositionInfoBody())?.let(DlnaSoap::parsePositionInfo)
                ?.also { position ->
                    // Log readings that don't follow on from the last one (a seek, a stall, a jump back).
                    val last = lastLoggedPosition
                    if (last == null || position < last || position - last > 3_000) {
                        CastLog.add("TV position ${position / 1000}s")
                    }
                    lastLoggedPosition = position
                }
        }
    }

    @Volatile private var lastLoggedPosition: Long? = null
    @Volatile private var lastLoggedState: String? = null

    actual suspend fun getTransportState(device: DlnaDevice): String? = withContext(Dispatchers.IO) {
        commandLock.ifFree {
            soapResponse(device, "GetTransportInfo", DlnaSoap.buildGetTransportInfoBody())?.let(DlnaSoap::parseTransportState)
                ?.also { if (it != lastLoggedState) CastLog.add("TV state: $it") }
                ?.also { lastLoggedState = it }
        }
    }

    actual suspend fun seekPlayback(device: DlnaDevice, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        commandLock.withLockBlocking {
            val target = DlnaSoap.formatDurationMs(positionMs)
            // Most TVs take REL_TIME; some only ABS_TIME.
            soap(device, "Seek", DlnaSoap.buildSeekBody(target = target)) ||
                soap(device, "Seek", DlnaSoap.buildSeekBody(target = target, unit = "ABS_TIME"))
        }
    }

    actual suspend fun setVolume(device: DlnaDevice, volumePercent: Int): Boolean = withContext(Dispatchers.IO) {
        commandLock.withLockBlocking {
            soap(
                device,
                "SetVolume",
                DlnaSoap.buildSetVolumeBody(desiredVolume = volumePercent.coerceIn(0, 100)),
                url = device.renderingControlUrl ?: device.controlUrl,
                serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
            )
        }
    }

    actual suspend fun getLocalIpAddress(): String? = withContext(Dispatchers.IO) {
        // The Wi-Fi address, never the VPN's, which TVs can't reach.
        (localNetwork ?: refreshLocalNetwork())?.ownAddress?.hostAddress?.let { return@withContext it }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback || intf.name.contains("rmnet", ignoreCase = true)) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains(":")) continue
                    if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.")) {
                        Log.i(TAG, "Local IP candidate $host from ${intf.name}")
                        return@withContext host
                    }
                }
            }
            val wifi = appContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") {
                    Log.i(TAG, "Local IP from WifiManager: $ip")
                    return@withContext ip
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpAddress failed: ${e.message}")
            null
        }
    }

    actual fun stopProxy() {
        relay?.close()
        relay = null
        lastVideoUrl = null
        stopRelayService()
    }
}
