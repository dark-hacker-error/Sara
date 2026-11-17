package com.sara.assistant.core

import android.util.Log

/** Thin logging facade so every module tags consistently and release builds stay quiet. */
object SaraLog {
    private const val TAG = "Sara"

    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, t: Throwable? = null) = Log.e(TAG, message, t)
}
