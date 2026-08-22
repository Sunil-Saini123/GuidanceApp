package com.example.floatingassistant.pathgenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GroqProxyClient — Handles HTTP networking calls to the Groq Proxy API server.
 */
public class GroqProxyClient {

    private static final String TAG = "GroqProxyClient";

    // Default Proxy URL (can be overridden via setProxyUrl or constructor)
    private static final String DEFAULT_PROXY_URL = "http://10.0.2.2:8080/v1/chat/completions";
    private static final int DEFAULT_TIMEOUT_MS = 10000;

    private String proxyUrl;
    private int timeoutMs;
    private String modelName;

    public GroqProxyClient(String proxyUrl, int timeoutMs, String modelName) {
        this.proxyUrl = (proxyUrl != null && !proxyUrl.isEmpty()) ? proxyUrl : DEFAULT_PROXY_URL;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.modelName = (modelName != null && !modelName.isEmpty()) ? modelName : "llama-3.3-70b-versatile";
    }

    public GroqProxyClient() {
        this(DEFAULT_PROXY_URL, DEFAULT_TIMEOUT_MS, "llama-3.3-70b-versatile");
    }

    public void setProxyUrl(String proxyUrl) {
        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            this.proxyUrl = proxyUrl;
        }
    }

    /**
     * Sends a path generation request to the Groq proxy server and returns the raw JSON string response.
     */
    public String sendRequest(PathRequest request) throws Exception {
        String systemPrompt = PromptBuilder.SYSTEM_PROMPT;
        String userPrompt = PromptBuilder.buildUserPrompt(request);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.put(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.put(userMsg);

        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);

        AppLogger.d(TAG, "Posting request to Groq Proxy URL: " + proxyUrl);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(proxyUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);

            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(input, 0, input.length);
            }

            int statusCode = connection.getResponseCode();
            AppLogger.d(TAG, "Groq Proxy response status: " + statusCode);

            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (is == null) {
                throw new Exception("HTTP error code: " + statusCode + " with null response stream");
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
            reader.close();

            String responseStr = responseBuilder.toString();
            if (statusCode < 200 || statusCode >= 300) {
                throw new Exception("Groq Proxy returned HTTP " + statusCode + ": " + responseStr);
            }

            return extractContentFromResponse(responseStr);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Extracts the text message content from OpenAI/Groq standard JSON format.
     */
    private String extractContentFromResponse(String rawJson) throws Exception {
        JSONObject root = new JSONObject(rawJson);

        // If proxy directly returned content JSON:
        if (root.has("destination") && root.has("path")) {
            return rawJson;
        }

        // Standard OpenAI/Groq API structure
        if (root.has("choices")) {
            JSONArray choices = root.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                if (firstChoice.has("message")) {
                    JSONObject message = firstChoice.getJSONObject("message");
                    return message.optString("content", "");
                }
            }
        }

        return rawJson;
    }
}
