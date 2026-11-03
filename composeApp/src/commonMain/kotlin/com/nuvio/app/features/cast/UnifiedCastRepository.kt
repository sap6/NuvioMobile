package com.nuvio.app.features.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

sealed interface UnifiedCastState {
    data object Idle : UnifiedCastState
    data object Scanning : UnifiedCastState
    data class DevicesFound(val devices: List<UnifiedCastDevice>) : UnifiedCastState
    data object NoDevices : UnifiedCastState
    data class Casting(val device: UnifiedCastDevice, val proxyUrl: String) : UnifiedCastState
    data class Error(val message: String) : UnifiedCastState
}

@OptIn(ExperimentalAtomicApi::class)
object UnifiedCastRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<UnifiedCastState>(UnifiedCastState.Idle)
    val state: StateFlow<UnifiedCastState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<UnifiedCastDevice>>(emptyList())
    val devices: StateFlow<List<UnifiedCastDevice>> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var castJob: Job? = null

    private var currentDevice: UnifiedCastDevice? = null
    val activeDevice: UnifiedCastDevice? get() = currentDevice
    private var currentProxyUrl: String? = null
    var isPaused = false
        private set
    var volume: Float = 1.0f
        private set

    /** What's being cast, so a subtitle change can send the same video again. */
    private var currentRequest: DlnaCastRequest? = null

    /**
     * Remote-control commands, run one at a time in the order given. When taps arrive faster than the
     * TV answers, only the latest of each kind is sent: three taps of +10s become one seek of +30s,
     * and a volume drag sends its final level rather than every step.
     */
    private sealed interface RemoteCommand {
        data class PlayState(val play: Boolean) : RemoteCommand
        data class Seek(val positionMs: Long) : RemoteCommand
        data class Volume(val fraction: Float) : RemoteCommand
        data class Subtitle(val url: String?, val headers: Map<String, String>, val positionMs: Long) : RemoteCommand
    }

    private val commands = Channel<RemoteCommand>(Channel.UNLIMITED)

    /** Seeks waiting to be sent; while any are, the TV's reported position is out of date. */
    private val pendingSeeks = AtomicInt(0)
    private val pendingPlayStates = AtomicInt(0)

    /** Until these pass, readings from the TV are ignored: it reports the old state for a moment after a command. */
    @Volatile private var positionSettlesAt: TimeMark? = null
    @Volatile private var playStateSettlesAt: TimeMark? = null

    init {
        scope.launch { runCommands() }
    }

    private suspend fun runCommands() {
        for (first in commands) {
            val batch = mutableListOf(first)
            while (true) batch += commands.tryReceive().getOrNull() ?: break
            val seeks = batch.count { it is RemoteCommand.Seek }
            val playStates = batch.count { it is RemoteCommand.PlayState }
            val dev = currentDevice
            CastLog.add("Phone: running ${batch.joinToString { it.describe() }}")
            try {
                if (dev != null) {
                    batch.lastOrNull { it is RemoteCommand.Subtitle }?.let { applySubtitle(dev, it as RemoteCommand.Subtitle) }
                    batch.lastOrNull { it is RemoteCommand.Seek }?.let { applySeek(dev, (it as RemoteCommand.Seek).positionMs) }
                    batch.lastOrNull { it is RemoteCommand.PlayState }?.let { applyPlayState(dev, (it as RemoteCommand.PlayState).play) }
                    batch.lastOrNull { it is RemoteCommand.Volume }?.let { applyVolume(dev, (it as RemoteCommand.Volume).fraction) }
                }
            } catch (e: Exception) {
                CastLog.add("Phone: command failed: ${e.message}")
            } finally {
                pendingSeeks.addAndFetch(-seeks)
                pendingPlayStates.addAndFetch(-playStates)
                if (seeks > 0) positionSettlesAt = TimeSource.Monotonic.markNow() + 1500.milliseconds
                if (playStates > 0) playStateSettlesAt = TimeSource.Monotonic.markNow() + 2000.milliseconds
            }
        }
    }

    private suspend fun applySeek(dev: UnifiedCastDevice, positionMs: Long) {
        when (dev.protocol) {
            CastProtocol.DLNA, CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.seekPlayback(it, positionMs) }
            CastProtocol.CHROMECAST -> withContext(Dispatchers.Main) { ChromecastPlatform.seek(positionMs) }
            CastProtocol.AIRPLAY -> {}
        }
    }

    private suspend fun applyPlayState(dev: UnifiedCastDevice, play: Boolean) {
        when (dev.protocol) {
            CastProtocol.DLNA, CastProtocol.DIAL -> dev.dlnaDevice?.let {
                if (play) DlnaCastPlatform.resumePlayback(it) else DlnaCastPlatform.pausePlayback(it)
            }
            CastProtocol.CHROMECAST -> withContext(Dispatchers.Main) {
                if (play) ChromecastPlatform.resume() else ChromecastPlatform.pause()
            }
            CastProtocol.AIRPLAY -> {}
        }
    }

    private suspend fun applyVolume(dev: UnifiedCastDevice, fraction: Float) {
        when (dev.protocol) {
            CastProtocol.CHROMECAST -> withContext(Dispatchers.Main) { ChromecastPlatform.setVolume(fraction) }
            CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.setVolume(it, (fraction * 100).toInt()) }
            else -> {}
        }
    }

    private suspend fun applySubtitle(dev: UnifiedCastDevice, command: RemoteCommand.Subtitle) {
        val device = dev.dlnaDevice ?: return
        val request = (currentRequest ?: return).copy(subtitleUrl = command.url, subtitleHeaders = command.headers)
        currentRequest = request
        // Sending the video again restarts it; hold position readings until it has caught up.
        positionSettlesAt = TimeSource.Monotonic.markNow() + 6000.milliseconds
        DlnaCastPlatform.changeSubtitle(device, request, command.positionMs)
        isPaused = false
        positionSettlesAt = TimeSource.Monotonic.markNow() + 1500.milliseconds
    }

    private fun RemoteCommand.describe(): String = when (this) {
        is RemoteCommand.PlayState -> if (play) "play" else "pause"
        is RemoteCommand.Seek -> "seek to ${positionMs / 1000}s"
        is RemoteCommand.Volume -> "volume ${(fraction * 100).toInt()}"
        is RemoteCommand.Subtitle -> "subtitle ${url?.let(CastLog::shortUrl) ?: "off"}"
    }

    /** Forgets queued commands and settling windows, so nothing carries over to the next cast. */
    private fun resetRemoteState() {
        while (commands.tryReceive().isSuccess) Unit
        pendingSeeks.store(0)
        pendingPlayStates.store(0)
        positionSettlesAt = null
        playStateSettlesAt = null
    }

    private fun send(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Seek -> pendingSeeks.addAndFetch(1)
            is RemoteCommand.PlayState -> pendingPlayStates.addAndFetch(1)
            else -> {}
        }
        commands.trySend(command)
    }

    fun startScan() {
        // Opening the cast sheet while casting must not drop the casting state, or the phone takes
        // playback back and loses its remote controls.
        if (_state.value is UnifiedCastState.Scanning || _state.value is UnifiedCastState.Casting) return
        scanJob?.cancel()
        _state.value = UnifiedCastState.Scanning
        scanJob = scope.launch {
            try {
                val dlnaDeferred = async(Dispatchers.IO) {
                    try { DlnaCastPlatform.scanDevices(4000) } catch (_: Exception) { DlnaScanResult.Success(emptyList()) }
                }
                val castDeferred = async(Dispatchers.IO) {
                    try { ChromecastPlatform.scanDevices(4000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val dialDeferred = async(Dispatchers.IO) {
                    try { DialPlatform.scanDevices(3000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val airPlayDeferred = async(Dispatchers.IO) {
                    try { AirPlayPlatform.scanDevices(3000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val dlnaResult = dlnaDeferred.await()
                val castDevices = castDeferred.await()
                val dialDevices = dialDeferred.await()
                val airPlayDevices = airPlayDeferred.await()

                val dlnaUnified = when (dlnaResult) {
                    is DlnaScanResult.Success -> dlnaResult.devices.map { d ->
                        UnifiedCastDevice(
                            id = d.id,
                            name = d.friendlyName,
                            protocol = CastProtocol.DLNA,
                            dlnaDevice = d,
                            ipAddress = d.ipAddress
                        )
                    }
                    else -> emptyList()
                }
                val allUnsorted = castDevices + airPlayDevices + dialDevices + dlnaUnified
                val priority = mapOf(CastProtocol.CHROMECAST to 0, CastProtocol.AIRPLAY to 1, CastProtocol.DIAL to 2, CastProtocol.DLNA to 3)
                val all = allUnsorted.sortedBy { priority[it.protocol] ?: 99 }.distinctBy { it.id }
                _devices.value = all
                _state.value = if (all.isEmpty()) UnifiedCastState.NoDevices else UnifiedCastState.DevicesFound(all)
            } catch (e: Exception) {
                _state.value = UnifiedCastState.Error(e.message ?: "Scan error")
            }
        }
    }

    fun castToDevice(device: UnifiedCastDevice, dlnaRequest: DlnaCastRequest, chromecastPoster: String? = null) {
        castJob?.cancel()
        castJob = scope.launch {
            try {
                _state.value = UnifiedCastState.Casting(device, "")
                resetRemoteState()
                currentRequest = dlnaRequest
                isPaused = false
                CastLog.add("Phone: cast to ${device.name} (${device.protocol})")
                // Reuse same proxy preparation for both protocols (headers/ torrent)
                val proxyUrl = withContext(Dispatchers.IO) { DlnaCastPlatform.prepareProxyUrl(dlnaRequest) }
                currentDevice = device
                currentProxyUrl = proxyUrl
                _state.value = UnifiedCastState.Casting(device, proxyUrl)

                val success = withContext(Dispatchers.Main) {
                    when (device.protocol) {
                        CastProtocol.DLNA -> {
                            val d = device.dlnaDevice ?: return@withContext false
                            DlnaCastPlatform.castToDevice(d, dlnaRequest, proxyUrl)
                        }
                        CastProtocol.CHROMECAST -> {
                            val req = ChromecastMediaRequest(
                                proxyUrl = proxyUrl,
                                title = dlnaRequest.title,
                                subtitle = dlnaRequest.subtitle,
                                subtitleUrl = dlnaRequest.subtitleUrl,
                                subtitleName = dlnaRequest.subtitleName,
                                subtitleLanguage = dlnaRequest.subtitleLanguage ?: "en",
                                subtitles = dlnaRequest.subtitles,
                                mimeType = dlnaRequest.mimeType,
                                durationMs = dlnaRequest.durationMs,
                                startPositionMs = dlnaRequest.startPositionMs,
                                posterUrl = chromecastPoster ?: dlnaRequest.posterUrl,
                            )
                            ChromecastPlatform.castToDevice(device, req)
                        }
                        CastProtocol.DIAL -> {
                            // DIAL launch via Application-URL POST (Fire TV, Roku) - stub, fallback to DLNA if available
                            // For now, try DLNA as fallback
                            val d = device.dlnaDevice
                            if (d != null) DlnaCastPlatform.castToDevice(d, dlnaRequest, proxyUrl) else false
                        }
                        CastProtocol.AIRPLAY -> {
                            // AirPlay not yet implemented on Android (needs NsdManager + AirPlay protocol)
                            false
                        }
                    }
                }

                if (!success) {
                    _state.value = UnifiedCastState.Error("Failed to send to ${device.name}")
                }
            } catch (e: Exception) {
                _state.value = UnifiedCastState.Error(e.message ?: "Cast error")
            }
        }
    }

    fun pause() {
        if (currentDevice == null) return
        isPaused = true
        send(RemoteCommand.PlayState(play = false))
    }

    fun resume() {
        if (currentDevice == null) return
        isPaused = false
        send(RemoteCommand.PlayState(play = true))
    }

    fun seek(positionMs: Long) {
        if (currentDevice == null) return
        send(RemoteCommand.Seek(positionMs.coerceAtLeast(0)))
    }

    fun stop() {
        castJob?.cancel()
        val dev = currentDevice
        // Immediate UI - X works instantly
        isPaused = false
        currentDevice = null
        currentProxyUrl = null
        currentRequest = null
        resetRemoteState()
        CastLog.add("Phone: stop casting")
        _state.value = UnifiedCastState.Idle
        scope.launch(Dispatchers.IO) {
            try {
                when (dev?.protocol) {
                    CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.stopPlayback(it) }
                    CastProtocol.CHROMECAST -> ChromecastPlatform.stop()
                    CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.stopPlayback(it) }
                    CastProtocol.AIRPLAY -> {}
                    else -> {}
                }
            } catch (_: Exception) {}
            try {
                when (dev?.protocol) {
                    CastProtocol.DLNA -> DlnaCastPlatform.stopProxy()
                    CastProtocol.CHROMECAST -> {
                        ChromecastPlatform.disconnect()
                        DlnaCastPlatform.stopProxy()
                    }
                    CastProtocol.DIAL -> DlnaCastPlatform.stopProxy()
                    else -> DlnaCastPlatform.stopProxy()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * The TV's playback position, or null when it can't be trusted right now: a seek is waiting to be
     * sent or has only just been sent, or the TV is busy with a command. Callers keep counting locally.
     */
    suspend fun getPosition(): Long? {
        val dev = currentDevice ?: return null
        if (pendingSeeks.load() > 0 || positionSettlesAt?.hasNotPassedNow() == true) return null
        val position = when (dev.protocol) {
            CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.getPositionInfo(it) }
            CastProtocol.CHROMECAST -> ChromecastPlatform.getPosition()
            CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.getPositionInfo(it) }
            CastProtocol.AIRPLAY -> null
        }
        // A seek may have been sent while this reading was on its way.
        return position.takeIf { pendingSeeks.load() == 0 && positionSettlesAt?.hasNotPassedNow() != true }
    }

    /**
     * Whether the TV is paused, so the phone's button follows changes made with the TV remote. Null when
     * unknown or while a play/pause from the phone is still settling.
     */
    suspend fun getRemotePaused(): Boolean? {
        val dev = currentDevice ?: return null
        if (pendingPlayStates.load() > 0 || playStateSettlesAt?.hasNotPassedNow() == true) return null
        val state = when (dev.protocol) {
            CastProtocol.DLNA, CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.getTransportState(it) }
            else -> null
        } ?: return null
        if (pendingPlayStates.load() > 0 || playStateSettlesAt?.hasNotPassedNow() == true) return null
        return when (state) {
            "PAUSED_PLAYBACK" -> true
            "PLAYING" -> false
            else -> null
        }.also { if (it != null) isPaused = it }
    }

    fun setVolume(volumeFraction: Float) {
        if (currentDevice == null) return
        val clamped = volumeFraction.coerceIn(0f, 1f)
        volume = clamped
        send(RemoteCommand.Volume(clamped))
    }

    fun setActiveSubtitleTrack(trackId: Long?) {
        val dev = currentDevice ?: return
        scope.launch(Dispatchers.Main) {
            when (dev.protocol) {
                CastProtocol.CHROMECAST -> ChromecastPlatform.setActiveTrack(trackId)
                else -> {}
            }
        }
    }

    /**
     * Shows a subtitle file (or none, with a null [url]) on a DLNA TV, from [positionMs]. Chromecast
     * switches tracks with [setActiveSubtitleTrack] instead.
     */
    fun setCastSubtitle(url: String?, headers: Map<String, String>, positionMs: Long) {
        val dev = currentDevice ?: return
        if (dev.protocol != CastProtocol.DLNA && dev.protocol != CastProtocol.DIAL) return
        if (url == currentRequest?.subtitleUrl) return
        send(RemoteCommand.Subtitle(url, headers, positionMs))
    }

    // Capability sniffing - checks GetProtocolInfo for DLNA, assumes modern for Cast
    suspend fun getCapabilities(device: UnifiedCastDevice): DeviceCapabilities = withContext(Dispatchers.IO) {
        when (device.protocol) {
            CastProtocol.CHROMECAST -> DeviceCapabilities(supportsHevc = true, supportsAv1 = true, supportsHls = true, supportsMkv = true)
            CastProtocol.AIRPLAY -> DeviceCapabilities(supportsHevc = true, supportsAv1 = false, supportsHls = true)
            CastProtocol.DIAL -> DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = false)
            CastProtocol.DLNA -> {
                // Old Samsung DLNA typically only mp4/avc/aac, try GetProtocolInfo
                try {
                    val d = device.dlnaDevice ?: return@withContext DeviceCapabilities()
                    // Conservative: assume DLNA without hevc/av1
                    val isSamsungOld = d.modelName?.contains("Samsung", ignoreCase = true) == true || d.locationUrl.contains("smp_")
                    if (isSamsungOld) DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = false)
                    else DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = true)
                } catch (_: Exception) { DeviceCapabilities() }
            }
        }
    }

    // WOL placeholder - send magic packet to TV MAC if known (future: store MAC from discovery)
    fun wakeOnLan(macAddress: String, broadcastIp: String = "255.255.255.255") {
        // Stub for universal WOL - magic packet 6x 0xFF + 16x MAC
        // Will be implemented with DatagramSocket to 9/UDP when MAC is discovered via ARP
    }

    fun reset() {
        _devices.value = emptyList()
        _state.value = UnifiedCastState.Idle
    }
}
