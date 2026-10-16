package com.iwhalecloud.byai.gateway.direct;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * CubeAPI (E2B-compatible) lifecycle calls and CubeProxy sandbox traffic. The API key and
 * traffic tokens stay in this backend; nothing here is returned to a browser.
 */
public class CubeSandboxClient {

    /** Server never kills a Direct runtime on its own timeout; the work-lease controller pauses it. */
    public static final int NEVER_TIMEOUT = -1;
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final int ENVD_PORT = 49983;

    private final DirectRuntimeProperties.Cube config;
    private final OkHttpClient api;
    private final OkHttpClient traffic;

    public CubeSandboxClient(DirectRuntimeProperties.Cube config) {
        this(config, new OkHttpClient.Builder()
            .connectTimeout(config.getRequestTimeout())
            .readTimeout(config.getRequestTimeout())
            .callTimeout(Duration.ofMinutes(3))
            .build());
    }

    CubeSandboxClient(DirectRuntimeProperties.Cube config, OkHttpClient api) {
        this.config = config;
        this.api = api;
        // Direct turns stream for as long as the Agent runs; only connect is bounded.
        this.traffic = api.newBuilder().readTimeout(Duration.ZERO).callTimeout(Duration.ZERO).build();
    }

    /** Cube sandbox state as reported by CubeAPI. */
    public record CubeSandbox(String sandboxId, String state, String trafficAccessToken, String envdAccessToken,
                              Map<String, String> metadata) {
        @Override
        public String toString() {
            return "CubeSandbox[sandboxId=" + sandboxId + ", state=" + state + "]";
        }
    }

    /** A Cube call failed; {@code outcomeUnknown} means the side effect may or may not exist. */
    public static class CubeException extends RuntimeException {
        private final int status;
        private final boolean outcomeUnknown;
        private final boolean templateUnavailable;

        public CubeException(String message, int status, boolean outcomeUnknown, Throwable cause) {
            this(message, status, outcomeUnknown, false, cause);
        }

        public CubeException(String message, int status, boolean outcomeUnknown, boolean templateUnavailable,
                             Throwable cause) {
            super(message, cause);
            this.status = status;
            this.outcomeUnknown = outcomeUnknown;
            this.templateUnavailable = templateUnavailable;
        }

        /** The pinned template does not exist or is not READY on CubeMaster. */
        public boolean templateUnavailable() {
            return templateUnavailable;
        }

        public int status() {
            return status;
        }

        public boolean outcomeUnknown() {
            return outcomeUnknown;
        }

        /** CubeMaster rejects scheduling for capacity/quota with 429/503 style responses. */
        public boolean capacity() {
            return status == 429 || status == 503 || status == 507;
        }
    }

    public CubeSandbox create(String templateId, Map<String, String> envVars, Map<String, String> metadata) {
        JSONObject payload = new JSONObject(true);
        payload.put("templateID", templateId);
        payload.put("timeout", NEVER_TIMEOUT);
        payload.put("envVars", envVars);
        payload.put("metadata", metadata);
        JSONObject lifecycle = new JSONObject(true);
        lifecycle.put("onTimeout", "pause");
        lifecycle.put("autoResume", false);
        payload.put("lifecycle", lifecycle);
        // Restrict public sandbox traffic so CubeProxy requires the per-sandbox traffic token.
        JSONObject network = new JSONObject(true);
        network.put("allowPublicTraffic", false);
        if (!config.getAllowOut().isEmpty()) network.put("allowOut", config.getAllowOut());
        payload.put("network", network);
        Request request = apiRequest("/sandboxes").post(RequestBody.create(payload.toJSONString(), JSON_TYPE)).build();
        // Any failure after the request may have reached CubeAPI leaves the create outcome unknown.
        return parse(execute(request, true));
    }

    public Optional<CubeSandbox> get(String sandboxId) {
        Request request = apiRequest("/sandboxes/" + pathSegment(sandboxId)).get().build();
        try (Response response = api.newCall(request).execute()) {
            if (response.code() == 404) return Optional.empty();
            return Optional.of(parse(body(response)));
        }
        catch (IOException e) {
            throw new CubeException("CubeAPI get failed", 0, false, e);
        }
    }

    /** Running and paused sandboxes whose metadata contains every given pair. */
    public List<CubeSandbox> listByMetadata(Map<String, String> metadata) {
        StringBuilder query = new StringBuilder();
        metadata.forEach((key, value) -> {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        HttpUrl url = HttpUrl.get(config.getApiUrl().replaceAll("/+$", "") + "/v2/sandboxes").newBuilder()
            .addQueryParameter("metadata", query.toString())
            .addQueryParameter("state", "running,paused")
            .build();
        Request request = withApiKey(new Request.Builder().url(url)).get().build();
        JSONArray items = JSON.parseArray(execute(request, false));
        List<CubeSandbox> result = new ArrayList<>();
        for (int index = 0; items != null && index < items.size(); index++) {
            CubeSandbox sandbox = parse(items.getJSONObject(index).toJSONString());
            // Filter locally as well: an older CubeAPI may ignore the metadata query.
            if (metadata.entrySet().stream().allMatch(entry -> entry.getValue().equals(sandbox.metadata().get(entry.getKey())))) {
                result.add(sandbox);
            }
        }
        return result;
    }

    /** Connect resumes a paused sandbox and returns its current traffic credentials. */
    public CubeSandbox connect(String sandboxId) {
        Request request = apiRequest("/sandboxes/" + pathSegment(sandboxId) + "/connect")
            .post(RequestBody.create("{}", JSON_TYPE)).build();
        return parse(execute(request, true));
    }

    /** Delete an instance; an instance that is already gone counts as deleted. */
    public void delete(String sandboxId) {
        Request request = apiRequest("/sandboxes/" + pathSegment(sandboxId)).delete().build();
        try {
            execute(request, true);
        }
        catch (CubeException e) {
            if (e.status() != 404) throw e;
        }
    }

    public void pause(String sandboxId) {
        Request request = apiRequest("/sandboxes/" + pathSegment(sandboxId) + "/pause")
            .post(RequestBody.create("{}", JSON_TYPE)).build();
        execute(request, true);
    }

    /** Request to a sandbox port through CubeProxy; the proxy routes on the virtual Host. */
    public Request.Builder sandboxRequest(String sandboxId, String trafficToken, int port, String pathAndQuery) {
        HttpUrl url = HttpUrl.get("http://" + config.getProxyHost() + ":" + config.getProxyPort() + pathAndQuery);
        Request.Builder builder = new Request.Builder().url(url)
            .header("Host", port + "-" + sandboxId + "." + config.getSandboxDomain());
        if (StringUtils.isNotBlank(trafficToken)) builder.header("e2b-traffic-access-token", trafficToken);
        return builder;
    }

    /** Write one file inside the sandbox through envd as the given user. */
    public void writeFile(String sandboxId, String trafficToken, String envdToken, String path, String user, byte[] content) {
        HttpUrl url = HttpUrl.get("http://" + config.getProxyHost() + ":" + config.getProxyPort() + "/files").newBuilder()
            .addQueryParameter("path", path).addQueryParameter("username", user).build();
        Request.Builder builder = withApiKey(new Request.Builder().url(url))
            .header("Host", ENVD_PORT + "-" + pathSegment(sandboxId) + "." + config.getSandboxDomain());
        if (StringUtils.isNotBlank(trafficToken)) builder.header("e2b-traffic-access-token", trafficToken);
        if (StringUtils.isNotBlank(envdToken)) builder.header("X-Access-Token", envdToken);
        execute(builder.post(RequestBody.create(content, MediaType.get("application/octet-stream"))).build(), true);
    }

    public Call stream(Request request) {
        return traffic.newCall(request);
    }

    public Call call(Request request) {
        return api.newCall(request);
    }

    /** A page request whose redirects belong to the browser (they carry the viewer's query). */
    public Call callWithoutRedirects(Request request) {
        return api.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request);
    }

    private Request.Builder apiRequest(String path) {
        return withApiKey(new Request.Builder().url(config.getApiUrl().replaceAll("/+$", "") + path));
    }

    private Request.Builder withApiKey(Request.Builder builder) {
        if (StringUtils.isNotBlank(config.getApiKey())) builder.header("X-API-Key", config.getApiKey());
        return builder;
    }

    private String execute(Request request, boolean sideEffect) {
        try (Response response = api.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // A definite HTTP rejection proves the side effect did not happen, except 5xx/timeouts.
                String text = safeBody(response);
                // CubeMaster answers a missing or unbuilt template with 500/130404: a definite refusal.
                boolean templateMissing = text.contains("130404") || text.contains("template not found");
                boolean unknown = sideEffect && response.code() >= 500 && response.code() != 503 && !templateMissing;
                throw new CubeException("CubeAPI " + request.method() + " " + request.url().encodedPath()
                    + " returned " + response.code() + ": " + StringUtils.abbreviate(text, 300),
                    response.code(), unknown, templateMissing, null);
            }
            return body(response);
        }
        catch (IOException e) {
            throw new CubeException("CubeAPI " + request.method() + " " + request.url().encodedPath() + " failed",
                0, sideEffect, e);
        }
    }

    private static String body(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private static String safeBody(Response response) {
        try {
            return body(response);
        }
        catch (IOException e) {
            return "";
        }
    }

    private static CubeSandbox parse(String text) {
        JSONObject value = JSON.parseObject(StringUtils.defaultIfBlank(text, "{}"));
        Map<String, String> metadata = new LinkedHashMap<>();
        JSONObject raw = value.getJSONObject("metadata");
        if (raw != null) raw.forEach((key, item) -> metadata.put(key, item == null ? null : String.valueOf(item)));
        return new CubeSandbox(value.getString("sandboxID"), value.getString("state"),
            value.getString("trafficAccessToken"), value.getString("envdAccessToken"), metadata);
    }

    private static String pathSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid sandbox id");
        }
        return value;
    }
}
