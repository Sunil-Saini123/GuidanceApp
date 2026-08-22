package com.example.floatingassistant.pathgenerator;

import android.util.Log;

/**
 * AppLogger — Safe logging utility that delegates to android.util.Log when running
 * on Android runtime, and falls back to System.out/err during local JVM unit testing.
 */
public class AppLogger {

    public static void d(String tag, String msg) {
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[DEBUG][" + tag + "] " + msg);
        }
    }

    public static void i(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[INFO][" + tag + "] " + msg);
        }
    }

    public static void w(String tag, String msg) {
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[WARN][" + tag + "] " + msg);
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        try {
            Log.e(tag, msg, t);
        } catch (Throwable ignored) {
            System.err.println("[ERROR][" + tag + "] " + msg);
            if (t != null) t.printStackTrace();
        }
    }

    public static void e(String tag, String msg) {
        e(tag, msg, null);
    }
}
