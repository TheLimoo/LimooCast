package com.limoo.limoocast

import android.content.Intent

// Holds the one-shot MediaProjection consent so the foreground service
// (a different component, same process) can use it. The token is only
// valid for this process run; nothing is persisted.
object ProjectionHolder {
    var resultCode: Int = 0
    var resultData: Intent? = null
}

// Live capture stats, read by MainActivity on a timer. Volatile so the
// service thread and the UI thread see the same values.
object CastStats {
    @Volatile var running: Boolean = false
    @Volatile var connected: Boolean = false
    @Volatile var level: Float = 0f        // 0..1 peak in the last chunk
    @Volatile var bytesSent: Long = 0
    @Volatile var listeners: Int = 0
    @Volatile var error: String? = null
    @Volatile var silentChunks: Long = 0   // chunks that were all-zero
    @Volatile var totalChunks: Long = 0
}
