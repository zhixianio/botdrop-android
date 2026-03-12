package app.botdrop;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenclawWebUiConfigResolver {

    private static final String OPENCLAW_WEB_UI_TOKEN_KEY = "token";
    public static final int OPENCLAW_DEFAULT_WEB_UI_PORT = 18789;
    public static final String OPENCLAW_DEFAULT_WEB_UI_PATH = "/";
    public static final String OPENCLAW_DEFAULT_WEB_UI_URL =
            "http://127.0.0.1:" + OPENCLAW_DEFAULT_WEB_UI_PORT + OPENCLAW_DEFAULT_WEB_UI_PATH;

    private static final Pattern HOST_PORT_PATTERN =
            Pattern.compile("(?i)\\b(127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[[0-9a-f:]+\\]|[a-z0-9._-]+):(\\d{2,5})\\b");

    private OpenclawWebUiConfigResolver() {
    }

    @Nullable
    public static String resolveOpenclawWebUiUrl(@Nullable String configText, @Nullable String gatewayToken) {
        if (TextUtils.isEmpty(configText)) {
            return OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        String host = "127.0.0.1";
        int port = OPENCLAW_DEFAULT_WEB_UI_PORT;
        String basePath = OPENCLAW_DEFAULT_WEB_UI_PATH;

        try {
            JSONObject config = new JSONObject(configText);
            String normalizedHost = extractOpenclawHostFromJson(config);
            int configPort = extractOpenclawPortFromJson(config);
            String configBasePath = extractOpenclawControlUiBasePathFromJson(config);
            if (!TextUtils.isEmpty(normalizedHost) && isLocalWebUiHost(normalizeOpenclawHost(normalizedHost))) {
                host = normalizeOpenclawHost(normalizedHost);
                if (!TextUtils.isEmpty(host) && host.indexOf(':') >= 0 && !host.startsWith("[")) {
                    host = "[" + host + "]";
                }
            }
            if (configPort > 0) {
                port = configPort;
            }
            if (!TextUtils.isEmpty(configBasePath)) {
                basePath = normalizeOpenclawControlUiPath(configBasePath);
            }
        } catch (Exception ignored) {
        }

        if (TextUtils.isEmpty(host)) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = OPENCLAW_DEFAULT_WEB_UI_PORT;
        }
        if (TextUtils.isEmpty(basePath)) {
            basePath = OPENCLAW_DEFAULT_WEB_UI_PATH;
        }

        String baseUrl = "http://" + host + ":" + port + basePath;
        return appendGatewayTokenToWebUiUrl(baseUrl, gatewayToken);
    }

    @Nullable
    public static String extractOpenclawControlUiBasePathFromJson(@NonNull JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject gateway = root.optJSONObject("gateway");
        if (gateway != null) {
            JSONObject controlUi = gateway.optJSONObject("controlUi");
            if (controlUi != null) {
                String basePath = controlUi.optString("basePath", null);
                String normalized = normalizeOpenclawControlUiPath(basePath);
                if (!TextUtils.isEmpty(normalized)) {
                    return normalized;
                }
            }
        }

        JSONObject controlUi = root.optJSONObject("controlUi");
        if (controlUi != null) {
            String basePath = controlUi.optString("basePath", null);
            String normalized = normalizeOpenclawControlUiPath(basePath);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }

        String legacyBasePath = root.optString("controlUiBasePath", null);
        if (!TextUtils.isEmpty(legacyBasePath)) {
            String normalized = normalizeOpenclawControlUiPath(legacyBasePath);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }

        return null;
    }

    public static String normalizeOpenclawControlUiPath(@Nullable String rawPath) {
        if (TextUtils.isEmpty(rawPath)) {
            return OPENCLAW_DEFAULT_WEB_UI_PATH;
        }
        String normalized = rawPath.trim();
        if (TextUtils.isEmpty(normalized)) {
            return OPENCLAW_DEFAULT_WEB_UI_PATH;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String extractGatewayTokenFromConfig(@Nullable String configText) {
        if (TextUtils.isEmpty(configText)) {
            return null;
        }

        try {
            JSONObject config = new JSONObject(configText);
            return extractGatewayTokenFromJson(config);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static String extractGatewayTokenFromJson(@NonNull JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject gateway = root.optJSONObject("gateway");
        if (gateway == null) {
            return null;
        }

        JSONObject auth = gateway.optJSONObject("auth");
        if (auth != null) {
            String authToken = normalizeOpenclawString(auth.optString("token", null));
            if (!TextUtils.isEmpty(authToken)) {
                return authToken;
            }
        }

        String gatewayToken = normalizeOpenclawString(gateway.optString("token", null));
        if (!TextUtils.isEmpty(gatewayToken)) {
            return gatewayToken;
        }

        return normalizeOpenclawString(root.optString("token", null));
    }

    public static String appendGatewayTokenToWebUiUrl(String webUiUrl, String token) {
        if (TextUtils.isEmpty(token)) {
            return webUiUrl;
        }

        if (TextUtils.isEmpty(webUiUrl)) {
            return appendGatewayTokenToWebUiUrl(OPENCLAW_DEFAULT_WEB_UI_URL, token);
        }

        String trimmedUrl = webUiUrl.trim();
        if (TextUtils.isEmpty(trimmedUrl)) {
            return OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        if (hasQueryToken(trimmedUrl)) {
            return trimmedUrl;
        }

        String separator = trimmedUrl.contains("?") ? "&" : "?";
        if (trimmedUrl.endsWith("?") || trimmedUrl.endsWith("&")) {
            separator = "";
        }
        return trimmedUrl + separator + OPENCLAW_WEB_UI_TOKEN_KEY + "=" + token;
    }

    public static boolean hasQueryToken(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri parsed = Uri.parse(url);
            return !TextUtils.isEmpty(parsed.getQueryParameter(OPENCLAW_WEB_UI_TOKEN_KEY));
        } catch (Exception e) {
            String lowerUrl = url.toLowerCase();
            String marker = OPENCLAW_WEB_UI_TOKEN_KEY.toLowerCase() + "=";
            return lowerUrl.contains(marker);
        }
    }

    public static String normalizeOpenclawWebUiUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        String trimmed = trimUrlPunctuation(rawUrl.trim());
        if (TextUtils.isEmpty(trimmed)) return null;

        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            String scheme = parsed.getScheme();
            if (TextUtils.isEmpty(scheme)) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;

            String host = parsed.getHost();
            if (TextUtils.isEmpty(host)) return null;
            String normalizedHost = normalizeOpenclawHost(host);
            if (!isLocalWebUiHost(normalizedHost)) {
                return null;
            }
            int port = parsed.getPort();
            if (port <= 0) {
                port = OPENCLAW_DEFAULT_WEB_UI_PORT;
            }

            if (normalizedHost.indexOf(':') >= 0 && !normalizedHost.startsWith("[")) {
                normalizedHost = "[" + normalizedHost + "]";
            }

            StringBuilder url = new StringBuilder(scheme).append("://").append(normalizedHost);
            if (port > 0) {
                url.append(':').append(port);
            }
            return url.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String trimUrlPunctuation(String value) {
        if (TextUtils.isEmpty(value)) return value;
        return value.replaceAll("[\\)\\]\\}\\>,\\.;:\"]+$", "");
    }

    public static String extractOpenclawHostFromJson(@NonNull JSONObject root) {
        if (root == null) return null;
        String host = firstNonEmpty(
            normalizeOpenclawHost(root.optString("host", null)),
            normalizeOpenclawHost(root.optString("hostname", null)),
            normalizeOpenclawHost(root.optString("listenHost", null)),
            normalizeOpenclawHost(root.optString("address", null)),
            normalizeOpenclawHost(root.optString("bind", null))
        );

        if (TextUtils.isEmpty(host)) {
            String urlValue = root.optString("url", null);
            if (!TextUtils.isEmpty(urlValue)) {
                String normalized = normalizeOpenclawWebUiUrl(urlValue);
                if (!TextUtils.isEmpty(normalized)) {
                    try {
                        host = Uri.parse(normalized).getHost();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (TextUtils.isEmpty(host)) {
            String listen = root.optString("listen", null);
            if (!TextUtils.isEmpty(listen)) {
                String parsed = parseHostFromText(listen);
                if (!TextUtils.isEmpty(parsed)) host = parsed;
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway != null) {
                host = extractOpenclawHostFromJson(gateway);
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject server = root.optJSONObject("server");
            if (server != null) {
                host = extractOpenclawHostFromJson(server);
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject http = root.optJSONObject("http");
            if (http != null) {
                host = extractOpenclawHostFromJson(http);
            }
        }

        return normalizeOpenclawHost(host);
    }

    public static int extractOpenclawPortFromJson(@NonNull JSONObject root) {
        if (root == null) return -1;
        int port = firstPositiveInt(
            root.optInt("port", -1),
            root.optInt("listenPort", -1),
            root.optInt("httpPort", -1),
            root.optInt("gatewayPort", -1)
        );

        if (port <= 0) {
            port = parsePortFromText(root.optString("listen", null));
        }
        if (port <= 0) {
            port = parsePortFromText(root.optString("url", null));
        }
        if (port <= 0) {
            port = parsePortFromText(root.optString("endpoint", null));
        }

        if (port <= 0) {
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway != null) {
                port = extractOpenclawPortFromJson(gateway);
            }
        }

        if (port <= 0) {
            JSONObject server = root.optJSONObject("server");
            if (server != null) {
                port = extractOpenclawPortFromJson(server);
            }
        }

        if (port <= 0) {
            JSONObject http = root.optJSONObject("http");
            if (http != null) {
                port = extractOpenclawPortFromJson(http);
            }
        }

        return port;
    }

    public static String parseHostFromText(String value) {
        String hostPort = extractHostPortFromText(value);
        if (TextUtils.isEmpty(hostPort)) return null;
        int separatorIndex = hostPort.lastIndexOf(':');
        if (separatorIndex <= 0) return null;
        return hostPort.substring(0, separatorIndex);
    }

    public static int parsePortFromText(String value) {
        String hostPort = extractHostPortFromText(value);
        if (TextUtils.isEmpty(hostPort)) return -1;
        int separatorIndex = hostPort.lastIndexOf(':');
        if (separatorIndex <= 0 || separatorIndex + 1 >= hostPort.length()) return -1;
        try {
            return Integer.parseInt(hostPort.substring(separatorIndex + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String extractHostPortFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = HOST_PORT_PATTERN.matcher(text);
        while (matcher.find()) {
            String host = normalizeOpenclawHost(matcher.group(1));
            String port = matcher.group(2);
            if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(port)) {
                return host + ":" + port;
            }
        }
        return null;
    }

    public static int firstPositiveInt(int... values) {
        if (values == null) return -1;
        for (int value : values) {
            if (value > 0) return value;
        }
        return -1;
    }

    public static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value.trim();
        }
        return null;
    }

    public static String normalizeOpenclawHost(String host) {
        if (TextUtils.isEmpty(host)) return null;
        String normalized = host.trim();
        if ("*".equals(normalized) || "0.0.0.0".equals(normalized)) {
            return "127.0.0.1";
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("'") && normalized.endsWith("'") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    public static String normalizeOpenclawString(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.trim();
    }

    public static boolean isLocalWebUiHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        String normalized = host.toLowerCase();
        if (normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1") || normalized.equals("[::1]")) {
            return true;
        }
        if (normalized.equals("0.0.0.0") || normalized.equals("::") || normalized.equals("[::]")) {
            return true;
        }
        if (normalized.startsWith("localhost.")) {
            return true;
        }
        return normalized.startsWith("192.168.") || normalized.startsWith("10.") || normalized.startsWith("172.");
    }
}
