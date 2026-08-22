package com.example.floatingassistant.pathgenerator;

import android.content.Context;
import android.os.Build;

import com.example.floatingassistant.DeviceInfoWriter;
import com.example.floatingassistant.OemRomDetector;

import org.json.JSONObject;

/**
 * DeviceInfoGatherer — Collects device and custom OEM OS metadata
 * to pass along as context for the Groq path generation prompt.
 */
public class DeviceInfoGatherer {

    private static final String TAG = "DeviceInfoGatherer";

    public static JSONObject gather(Context context) {
        if (context != null) {
            try {
                return DeviceInfoWriter.INSTANCE.collect("path_generation");
            } catch (Exception e) {
                AppLogger.w(TAG, "Failed to collect from DeviceInfoWriter: " + e.getMessage());
            }
        }

        // Fallback synchronous gathering if context is null or collection failed
        JSONObject json = new JSONObject();
        try {
            json.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "unknown");
            json.put("model", Build.MODEL != null ? Build.MODEL : "unknown");
            json.put("brand", Build.BRAND != null ? Build.BRAND : "unknown");
            json.put("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "unknown");
            json.put("sdk_int", Build.VERSION.SDK_INT);

            OemRomDetector.OemResult oemResult = OemRomDetector.INSTANCE.detect();
            if (oemResult instanceof OemRomDetector.OemResult.Custom) {
                OemRomDetector.OemResult.Custom custom = (OemRomDetector.OemResult.Custom) oemResult;
                json.put("custom_os", custom.getName());
                json.put("custom_os_version", custom.getVersion());
            } else {
                json.put("custom_os", "stock");
                json.put("custom_os_version", "stock");
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error gathering fallback device info: " + e.getMessage(), e);
        }
        return json;
    }
}
