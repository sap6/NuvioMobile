package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncBoolean
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PauseOtherAudioSettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun pauseOtherAudioDefaultsToEnabledAndKeepsTheSavedChoiceAfterReload() {
        PlayerSettingsRepository.ensureLoaded()
        assertTrue(PlayerSettingsRepository.uiState.value.pauseOtherAudio)

        PlayerSettingsRepository.setPauseOtherAudio(false)
        PlayerSettingsRepository.onProfileChanged()

        assertFalse(PlayerSettingsRepository.uiState.value.pauseOtherAudio)
        assertEquals(false, PlayerSettingsStorage.loadPauseOtherAudio())
    }

    @Test
    fun pauseOtherAudioRoundTripsThroughSyncAndOlderPayloadsRestoreTheDefault() {
        PlayerSettingsRepository.setPauseOtherAudio(false)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("pause_other_audio"))

        PlayerSettingsRepository.setPauseOtherAudio(true)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(PlayerSettingsRepository.uiState.value.pauseOtherAudio)

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        PlayerSettingsRepository.onProfileChanged()

        assertNull(PlayerSettingsStorage.loadPauseOtherAudio())
        assertTrue(PlayerSettingsRepository.uiState.value.pauseOtherAudio)
    }
}
