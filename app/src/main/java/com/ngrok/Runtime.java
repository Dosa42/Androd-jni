package com.ngrok;

import android.util.Log;

/** Android loader and initializer for the installed ngrok JNI library. */
public final class Runtime {
    private static final Logger LOGGER = new Logger();
    private static volatile boolean initialized = false;

    public static Logger getLogger() {
        return LOGGER;
    }

    public static synchronized void load() {
        if (!initialized) {
            try {
                System.loadLibrary("ngrok_java");
                Log.i("ngrok", "System.loadLibrary(ngrok_java) succeeded");
            } catch (Throwable t) {
                Log.w("ngrok", "System.loadLibrary warning: " + t.getMessage());
            }

            try {
                init(LOGGER);
                Log.i("ngrok", "Native init(LOGGER) completed successfully");
            } catch (Throwable t) {
                Log.w("ngrok", "Native init note: " + t.getMessage());
            }

            initialized = true;
        }
    }

    public static native void init(Logger logger);

    public static final class Logger {
        public String getLevel() {
            return "INFO";
        }

        public void log(String level, String target, String message) {
            int priority;
            if (level == null) level = "INFO";
            switch (level.toUpperCase()) {
                case "ERROR": priority = Log.ERROR; break;
                case "WARN": priority = Log.WARN; break;
                case "DEBUG": priority = Log.DEBUG; break;
                case "TRACE": priority = Log.VERBOSE; break;
                default: priority = Log.INFO;
            }
            Log.println(priority, "ngrok", "[" + target + "] " + message);
        }
    }
}
