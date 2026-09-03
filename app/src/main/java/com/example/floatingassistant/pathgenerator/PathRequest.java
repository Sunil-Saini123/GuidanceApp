package com.example.floatingassistant.pathgenerator;

import org.json.JSONObject;

/**
 * PathRequest — Data container for all context parameters fed into the PathGenerator.
 */
public class PathRequest {

    private final UserIntent intent;
    private final String currentPosition;
    private final JSONObject deviceInfo;
    private final JSONObject navGraph;

    public PathRequest(UserIntent intent, String currentPosition, JSONObject deviceInfo, JSONObject navGraph) {
        this.intent = intent != null ? intent : new UserIntent("UNKNOWN", "");
        this.currentPosition = (currentPosition != null && !currentPosition.trim().isEmpty())
                ? currentPosition.trim()
                : "SettingsHomepage";
        this.deviceInfo = deviceInfo != null ? deviceInfo : new JSONObject();
        this.navGraph = navGraph != null ? navGraph : new JSONObject();
    }

    public PathRequest(UserIntent intent, String currentPosition) {
        this(intent, currentPosition, null, null);
    }

    public UserIntent getIntent() {
        return intent;
    }

    public String getCurrentPosition() {
        return currentPosition;
    }

    public JSONObject getDeviceInfo() {
        return deviceInfo;
    }

    public JSONObject getNavGraph() {
        return navGraph;
    }

    @Override
    public String toString() {
        return "PathRequest{" +
                "intent=" + intent +
                ", currentPosition='" + currentPosition + '\'' +
                ", deviceInfoPresent=" + (deviceInfo.length() > 0) +
                ", navGraphPresent=" + (navGraph.length() > 0) +
                '}';
    }
}
