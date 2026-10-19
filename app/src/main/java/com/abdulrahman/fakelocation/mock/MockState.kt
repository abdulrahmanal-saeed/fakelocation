package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.data.FakeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared state between the screens and the background service. */
object MockState {
    private val _session = MutableStateFlow<FakeSession?>(null)
    val session: StateFlow<FakeSession?> = _session

    fun set(session: FakeSession?) {
        _session.value = session
    }
}
