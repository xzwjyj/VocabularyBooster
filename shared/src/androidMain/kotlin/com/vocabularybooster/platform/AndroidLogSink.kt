package com.vocabularybooster.platform

import android.util.Log

/**
 * LogSink 的 Android 实现（ARCHITECTURE §5 平台矩阵）。
 */
public class AndroidLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }
}
