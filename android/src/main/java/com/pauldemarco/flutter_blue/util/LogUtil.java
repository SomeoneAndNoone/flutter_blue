package com.pauldemarco.flutter_blue.util;

import android.util.Log;

import com.pauldemarco.flutter_blue.FlutterBluePlugin;

public abstract class LogUtil {
    private static final String tag = FlutterBluePlugin.TAG;
    private static LogLevel logLevel = LogLevel.EMERGENCY;

    public enum LogLevel {
        EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFO, DEBUG
    }

    public static void log(LogLevel level, String message) {
        if (level.ordinal() <= logLevel.ordinal()) {
            Log.d(tag, message);
        }
    }

    public static void setLogLevel(LogLevel newLogLevel) {
        logLevel = newLogLevel;
    }
}
