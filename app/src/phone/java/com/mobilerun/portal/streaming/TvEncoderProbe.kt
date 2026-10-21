package com.mobilerun.portal.streaming

import android.os.Handler

/** Phone never enters TV negotiation preparation and never probes MediaCodec. */
internal object TvEncoderProbe {
    const val TIMEOUT_MS = 1500L
    enum class Result { OK, FAILED, TIMEOUT, STUCK, COOLDOWN }
    fun request(_width: Int, _height: Int, _fps: Int, _callbackHandler: Handler, done: (Result) -> Unit) {
        done(Result.OK)
    }
}
