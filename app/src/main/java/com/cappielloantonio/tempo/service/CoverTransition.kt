package com.cappielloantonio.tempo.service

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** The visible part of the local player's overlap, timed by the audio it is mixing. */
data class CoverTransition internal constructor(
    val token: Long,
    val fromIndex: Int,
    val fromId: String,
    val toIndex: Int,
    val toId: String,
    val autoMix: Boolean,
    val mode: Mode = Mode.PENDING,
    val progress: Float = 0f,
) {
    enum class Mode { PENDING, DISSOLVE, SLIDE, FAST_SLIDE }

    companion object {
        // The playback service and the full player run on the same main thread.
        private val state = MutableLiveData<CoverTransition?>()
        private var lastToken = 0L

        @JvmStatic
        fun updates(): LiveData<CoverTransition?> = state

        internal fun begin(fromIndex: Int, fromId: String, toIndex: Int, toId: String, autoMix: Boolean): Long {
            val token = maxOf(lastToken + 1, SystemClock.uptimeMillis())
            lastToken = token
            state.value = CoverTransition(token, fromIndex, fromId, toIndex, toId, autoMix)
            return token
        }

        internal fun advance(token: Long, mode: Mode, progress: Float) {
            val current = state.value?.takeIf { it.token == token } ?: return
            val nextProgress = maxOf(progress, current.progress)
            if (current.mode != mode || nextProgress != current.progress) {
                state.value = current.copy(mode = mode, progress = nextProgress)
            }
        }

        internal fun finish(token: Long) {
            if (state.value?.token == token) state.value = null
        }
    }
}
