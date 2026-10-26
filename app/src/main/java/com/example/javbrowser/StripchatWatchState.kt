package com.example.javbrowser

internal enum class StripchatWatchState {
    INACTIVE, RECORDING, STARTING, LIVE_RETRY, WAITING, CHECKING, UNKNOWN;

    companion object {
        fun resolve(active: Boolean, recording: Boolean, starting: Boolean, live: Boolean?, checked: Boolean) = when {
            !active -> INACTIVE
            recording -> RECORDING
            starting -> STARTING
            live == true -> LIVE_RETRY
            live == false -> WAITING
            !checked -> CHECKING
            else -> UNKNOWN
        }
    }
}
