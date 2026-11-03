package com.nuvio.app.features.cast

data class DlnaDevice(
    val id: String, // UDN
    val friendlyName: String,
    val modelName: String? = null,
    val manufacturer: String? = null,
    val locationUrl: String, // http://ip:7676/smp_15_...
    val controlUrl: String, // absolute URL for AVTransport
    val eventSubUrl: String? = null,
    val avTransportServiceType: String = "urn:schemas-upnp-org:service:AVTransport:1",
    val ipAddress: String? = null,
    val renderingControlUrl: String? = null,
)

data class DlnaCastRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val title: String,
    val subtitle: String? = null,
    val mimeType: String = "video/mp4",
    val codecHint: String? = null, // hevc, avc, av1 ...
    val durationMs: Long? = null,
    val subtitleUrl: String? = null,
    val subtitleName: String? = null,
    val subtitleLanguage: String? = null,
    val subtitles: List<SubtitleTrackInfo> = emptyList(),
    val subtitleHeaders: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
    val posterUrl: String? = null,
    /**
     * The built-in audio track to play, counted from 0 among the file's audio tracks in file order
     * (the phone player's numbering); null keeps the file's default. The language helps match it.
     */
    val audioTrackIndex: Int? = null,
    val audioTrackLanguage: String? = null,
    /** The built-in subtitle track to show, numbered like [audioTrackIndex]; null with [builtInSubtitlesOff] false keeps the file's default. */
    val subtitleTrackIndex: Int? = null,
    val subtitleTrackLanguage: String? = null,
    /** Hide the file's own subtitles, for "Off" or when a subtitle file is sent instead. */
    val builtInSubtitlesOff: Boolean = false,
)

sealed interface DlnaCastState {
    data object Idle : DlnaCastState
    data object Scanning : DlnaCastState
    data class DevicesFound(val devices: List<DlnaDevice>) : DlnaCastState
    data object NoDevices : DlnaCastState
    data class Casting(val device: DlnaDevice, val proxyUrl: String) : DlnaCastState
    data class Error(val message: String) : DlnaCastState
}

sealed interface DlnaScanResult {
    data class Success(val devices: List<DlnaDevice>) : DlnaScanResult
    data class Failure(val message: String) : DlnaScanResult
}
