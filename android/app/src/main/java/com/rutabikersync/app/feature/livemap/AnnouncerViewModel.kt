package com.rutabikersync.app.feature.livemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.announcer.AnnouncerEventKind
import com.rutabikersync.app.core.announcer.UniqueAnnouncer
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estado visual de `C6. Superposiciones de evento` (SCREENS.md C6):
 * - Pérdida de cobertura: banner `"X salió de la mesh"` con botón `"Ver en mapa"`.
 * - Reconexión: banner breve `"X se reconectó"`.
 * - Conexión inestable (≥ 3 pérdidas en 10 min): banner `"X tiene conexión inestable"`.
 */
data class AnnouncerUiState(
    val activeBanner: UniqueAnnouncer.AnnouncerOverlayBanner? = null,
    val isTtsMuted: Boolean = false,
    val ttsVolume: Float = 1.0f,
    val showViewOnMapButton: Boolean = false
)

class AnnouncerViewModel : ViewModel() {
    private var uniqueAnnouncer: UniqueAnnouncer? = null
    private var bannerCollectJob: Job? = null

    private val _uiState = MutableStateFlow(AnnouncerUiState())
    val uiState: StateFlow<AnnouncerUiState> = _uiState.asStateFlow()

    fun attachMeshEngine(
        meshEngine: MeshRelayEngine,
        localShortId: UShort = meshEngine.localShortId
    ): UniqueAnnouncer {
        uniqueAnnouncer?.cancelAllAndStop()
        val announcer = UniqueAnnouncer(
            scope = viewModelScope,
            localShortId = localShortId,
            meshEngine = meshEngine
        )
        uniqueAnnouncer = announcer
        announcer.start()

        bannerCollectJob?.cancel()
        bannerCollectJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            announcer.activeBanner.collect { banner ->
                _uiState.value = _uiState.value.copy(
                    activeBanner = banner,
                    showViewOnMapButton = banner?.kind == AnnouncerEventKind.PEER_LOST ||
                        banner?.kind == AnnouncerEventKind.UNSTABLE_CONNECTION
                )
            }
        }
        return announcer
    }

    fun setMuted(muted: Boolean) {
        uniqueAnnouncer?.ttsAnnouncer?.isMuted = muted
        _uiState.value = _uiState.value.copy(isTtsMuted = muted)
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        uniqueAnnouncer?.ttsAnnouncer?.volume = clamped
        _uiState.value = _uiState.value.copy(ttsVolume = clamped)
    }

    fun dismissBanner() {
        _uiState.value = _uiState.value.copy(activeBanner = null, showViewOnMapButton = false)
    }

    override fun onCleared() {
        uniqueAnnouncer?.cancelAllAndStop()
        super.onCleared()
    }
}
