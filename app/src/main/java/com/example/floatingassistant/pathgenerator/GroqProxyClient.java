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
import java.util.Arrays;
import java.util.List;

/**
 * GroqProxyClient - Handles HTTP networking calls to the live deployed Groq Proxy API server.
 *
 * Tries each model in MODEL_FALLBACK_LIST in order. HTTP 400/404/429 responses mean the
 * model is unavailable - skip to next model automatically.
 *
 * MODEL_FALLBACK_LIST contains only models confirmed active on the Groq API key
 * (verified via GET /openai/v1/models on 2026-09-03).
 */
public class GroqProxyClient {

    private static final String TAG = "GroqProxyClient";

    public static final String DEFAULT_PROXY_URL = "https://navigation-app-server.vercel.app/api/navigate";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30000;

    /**
     * Models confirmed active on this Groq API key (text input, text output, json_mode supported).
     * Tried in order - first success wins.
     */
    private static final List<String> MODEL_FALLBACK_LIST = Arrays.asList(
        "qwen/qwen3.8-27b",           // 27B, 131K ctx, json_mode + tools
        "qwen/qwen3.6-27b",           // 27B, 131K ctx, json_mode + tools
        "groq/compound",              // Groq compound, 131K ctx, json_mode
        "groq/compound-mini",         // Groq compound mini, 131K ctx
        "openai/gpt-oss-20b",         // 20B GPT OSS, 131K ctx
        "openai/gpt-oss-120b"         // 120B GPT OSS, 131K ctx (expensive, last resort)
    );

    public static final String DEFAULT_MODEL = MODEL_FALLBACK_LIST.get(0);

    /** Max characters of clean_page.json to include in the prompt.
     *  Keeps total request body well under Vercel's 4MB limit. */
    public static final int MAX_SCREEN_CONTENT_CHARS = 3000;

    private final String proxyUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String pinnedModel;

    public GroqProxyClient(String proxyUrl, int connectTimeoutMs, int readTimeoutMs, String modelName) {
        this.proxyUrl = (proxyUrl != null && !proxyUrl.trim().isEmpty()) ? proxyUrl.trim() : DEFAULT_PROXY_URL;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        this.pinnedModel = (modelName != null && !modelName.trim().isEmpty()) ? modelName.trim() : null;
    }

    public GroqProxyClient(String proxyUrl, int timeoutMs, String modelName) {
        this(proxyUrl, Math.min(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS), timeoutMs, modelName);
    }

    public GroqProxyClient() {
        this(DEFAULT_PROXY_URL, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, null);
    }

    public String getProxyUrl()  { return proxyUrl; }
    public String getModelName() { return pinnedModel != null ? pinnedModel : DEFAULT_MODEL; }

    public String sendRequest(PathRequest request) throws Exception {
        return sendDirectRequest(PromptBuilder.SYSTEM_PROMPT, PromptBuilder.buildUserPrompt(request));
    }

    /**
     * Sends pre-built prompts to Groq proxy. Tries each model in MODEL_FALLBACK_LIST
     * until one succeeds. HTTP 400/404/429 -> skip to next model.
     */
    public String sendDirectRequest(String systemPrompt, String userPrompt) throws Exception {
        List<String> modelsToTry = (pinnedModel != null) ? Arrays.asList(pinnedModel) : MODEL_FALLBACK_LIST;
        Exception lastException = null;

        for (String model : modelsToTry) {
            AppLogger.i(TAG, "Trying model: " + model);
            try {
                String result = attemptRequest(systemPrompt, userPrompt, model);
                AppLogger.i(TAG, "Model succeeded: " + model);
                return result;
            } catch (ModelUnavailableException e) {
                AppLogger.w(TAG, "Model " + model + " unavailable, trying next. Reason: " + e.getMessage());
                lastException = e;
            } catch (SocketTimeoutException e) {
                AppLogger.w(TAG, "Model " + model + " timed out, trying next");
                lastException = e;
            } catch (UnknownHostException e) {
                AppLogger.e(TAG, "No internet - aborting model fallback");
                throw e;
            }
        }

        String exhaustedMsg = "All " + modelsToTry.size() + " Groq models exhausted. Last: "
                + (lastException != null ? lastException.getMessage() : "unknown");
        throw new Exception(exhaustedMsg);
    }

    private static class ModelUnavailableException extends Exception {
        ModelUnavailableException(String msg) { super(msg); }
    }

    private String attemptRequest(String systemPrompt, String userPrompt, String model) throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
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
            AppLogger.d(TAG, "Request body size: " + input.length + " bytes, model=" + model);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(input, 0, input.length);
            }

            int statusCode = connection.getResponseCode();
            AppLogger.i(TAG, "HTTP " + statusCode + " for model=" + model);

            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? connection.getInputStream() : connection.getErrorStream();

            if (is == null) throw new Exception("HTTP " + statusCode + " with null response stream");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String responseStr = sb.toString();

            // Model-specific failures: skip to next
            if (statusCode == 400 || statusCode == 404 || statusCode == 429) {
                throw new ModelUnavailableException("HTTP " + statusCode + ": " + responseStr);
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new Exception("Groq Proxy error (HTTP " + statusCode + "): " + responseStr);
            }

            AppLogger.d(TAG, "Response received (" + responseStr.length() + " bytes) from model=" + model);
            return extractContentFromResponse(responseStr);

        } catch (ModelUnavailableException | SocketTimeoutException | UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.e(TAG, "Request error for model=" + model + ": " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String extractContentFromResponse(String rawJson) throws Exception {
        JSONObject root = new JSONObject(rawJson);
        if (root.has("destination") && root.has("path")) return rawJson;
        if (root.has("choices")) {
            JSONArray choices = root.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                if (firstChoice.has("message")) {
                    return firstChoice.getJSONObject("message").optString("content", "");
                }
            }
        }
        return rawJson;
    }
}