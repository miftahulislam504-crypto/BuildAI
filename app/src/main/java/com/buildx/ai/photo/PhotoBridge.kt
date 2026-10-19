package com.buildx.ai.photo

/** Carries the result of the photo screen back to the action that asked for it (exactly once). */
object PhotoBridge {
    @Volatile
    private var callback: ((Boolean, String) -> Unit)? = null

    fun expect(cb: (Boolean, String) -> Unit) {
        callback = cb
    }

    fun finish(ok: Boolean, message: String) {
        val cb = callback
        callback = null
        cb?.invoke(ok, message)
    }

    fun cancel() {
        callback = null
    }
}
