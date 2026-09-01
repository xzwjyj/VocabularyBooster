package com.vocabularybooster.platform

public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 日志端口（ARCHITECTURE §5）。
 * Android actual：`android.util.Log`；iOS actual：`os_log`。
 */
public interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)
}
