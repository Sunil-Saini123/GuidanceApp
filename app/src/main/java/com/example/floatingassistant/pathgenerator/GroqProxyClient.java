package com.example.floatingassistant.pathgenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * GroqProxyClient — Handles HTTP networking calls to the live deployed Groq Proxy API server.
 * Implements separated connect/read timeouts, try-with-resources stream management,
 * and specific error classification without leaking sensitive user prompt data into logs.
 */
public class GroqProxyClient {

    private static final String TAG = "GroqProxyClient";

    // Deployed Live Vercel Proxy Endpoint
    public static final String DEFAULT_PROXY_URL = "https://navigation-app-server.vercel.app/api/navigate";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;

    private String proxyUrl;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private String modelName;

    public GroqProxyClient(String proxyUrl, int connectTimeoutMs, int readTimeoutMs, String modelName) {
        this.proxyUrl = (proxyUrl != null && !proxyUrl.trim().isEmpty()) ? proxyUrl.trim() : DEFAULT_PROXY_URL;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        this.modelName = (modelName != null && !modelName.trim().isEmpty()) ? modelName.trim() : "llama-3.3-70b-versatile";
    }

    public GroqProxyClient(String proxyUrl, int timeoutMs, String modelName) {
        this(proxyUrl, Math.min(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS), timeoutMs, modelName);
    }

    public GroqProxyClient() {
        this(DEFAULT_PROXY_URL, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, "llama-3.3-70b-versatile");
    }

    public void setProxyUrl(String proxyUrl) {
        if (proxyUrl != null && !proxyUrl.trim().isEmpty()) {
            this.proxyUrl = proxyUrl.trim();
        }
    }

    public String getProxyUrl() {
        return proxyUrl;
    }

    public String getModelName() {
        return modelName;
    }

    /**
     * Sends a path generation request to the Groq proxy server and returns the raw JSON string response.
     */
    public String sendRequest(PathRequest request) throws Exception {
        String systemPrompt = PromptBuilder.SYSTEM_PROMPT;
        String userPrompt = PromptBuilder.buildUserPrompt(request);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 400);

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

        AppLogger.i(TAG, "🌐 CONNECTING TO PROXY: " + proxyUrl);
        AppLogger.d(TAG, "📤 Model: " + modelName + " | ConnectTimeout=" + connectTimeoutMs + "ms | ReadTimeout=" + readTimeoutMs + "ms");

        HttpURLConnection connection = null;
        try {
            URL url = new URL(proxyUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);

            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(input, 0, input.length);
            }

            int statusCode = connection.getResponseCode();
            AppLogger.i(TAG, "📡 PROXY RESPONSE CODE: HTTP " + statusCode);

            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (is == null) {
                throw new Exception("HTTP error code " + statusCode + " with null response stream");
            }

            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            String responseStr = responseBuilder.toString();
            if (statusCode < 200 || statusCode >= 300) {
                String errorCategory = classifyHttpError(statusCode);
                AppLogger.e(TAG, "❌ HTTP " + statusCode + " (" + errorCategory + ") from Proxy");
                throw new Exception("Groq Proxy error (HTTP " + statusCode + " - " + errorCategory + "): " + responseStr);
            }

            AppLogger.d(TAG, "📥 Raw Proxy Output Received (" + responseStr.length() + " bytes)");
            return extractContentFromResponse(responseStr);

        } catch (SocketTimeoutException e) {
            AppLogger.e(TAG, "⏱️ NETWORK TIMEOUT: Request timed out waiting for proxy/Groq response (" + e.getMessage() + ")");
            throw e;
        } catch (UnknownHostException e) {
            AppLogger.e(TAG, "🔌 NETWORK ERROR: Proxy host unreachable. Check device internet connection.");
            throw e;
        } catch (Exception e) {
            AppLogger.e(TAG, "💥 NETWORK / PROXY ERROR: " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String classifyHttpError(int statusCode) {
        switch (statusCode) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized - Check Server API Key";
            case 403: return "Forbidden";
            case 429: return "Rate Limit Exceeded";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway / Upstream Groq Unreachable";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "HTTP Error";
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
