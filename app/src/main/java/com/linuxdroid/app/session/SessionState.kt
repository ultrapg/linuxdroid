package com.linuxdroid.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SessionPhase {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    FAILED,
}

data class SessionSnapshot(
    val phase: SessionPhase = SessionPhase.STOPPED,
    val statusText: String = "Stopped",
    val activeGpuSummary: String = "",
    val resolutionText: String = "",
    val firstFrameReady: Boolean = false,
    val guestPid: Int = -1,
    val bootStep: Int = 0,
    val bootTotalSteps: Int = 6,
    val bootLogTail: List<String> = emptyList(),
    val errorMessage: String? = null,
    val compatibilityRetryUsed: Boolean = false,
    val startedAtMs: Long = 0L,
    val activeConfigSignature: SessionConfigSignature? = null,
)

object SessionState {
    private val mutableFlow = MutableStateFlow(SessionSnapshot())
    val state: StateFlow<SessionSnapshot> = mutableFlow

    val isRunningOrPaused: Boolean
        get() {
            val p = mutableFlow.value.phase
            return p == SessionPhase.STARTING || p == SessionPhase.RUNNING || p == SessionPhase.PAUSED
        }

    val isPaused: Boolean
        get() = mutableFlow.value.phase == SessionPhase.PAUSED

    fun update(transform: (SessionSnapshot) -> SessionSnapshot) {
        mutableFlow.value = transform(mutableFlow.value)
    }

    fun reset() {
        mutableFlow.value = SessionSnapshot()
    }
}
