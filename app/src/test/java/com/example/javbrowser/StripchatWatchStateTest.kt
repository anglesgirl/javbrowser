package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class StripchatWatchStateTest {
    @Test fun failedStatusLookupIsNeverDisplayedAsWaitingForBroadcast() {
        assertEquals(StripchatWatchState.CHECKING, StripchatWatchState.resolve(true, false, false, null, false))
        assertEquals(StripchatWatchState.UNKNOWN, StripchatWatchState.resolve(true, false, false, null, true))
        assertEquals(StripchatWatchState.WAITING, StripchatWatchState.resolve(true, false, false, false, true))
    }

    @Test fun distinguishesLiveButNotRecordingFromOfflineAndPreservesActualRecording() {
        assertEquals(StripchatWatchState.LIVE_RETRY, StripchatWatchState.resolve(true, false, false, true, true))
        assertEquals(StripchatWatchState.STARTING, StripchatWatchState.resolve(true, false, true, true, true))
        assertEquals(StripchatWatchState.RECORDING, StripchatWatchState.resolve(true, true, false, null, true))
        assertEquals(StripchatWatchState.INACTIVE, StripchatWatchState.resolve(false, false, false, true, true))
    }
}
