package app.botdrop;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Node mode control section for dashboard.
 */
public class NodeDashboardModeFragment extends Fragment implements DashboardRuntimeModeSection {

    private static final int DEFAULT_GATEWAY_PORT = 18789;
    private static final String NODE_STATE_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw-node";
    private static final String NODE_CONFIG_PATH = NODE_STATE_DIR + "/node.json";
    private static final String OPENCLAW_CONFIG_PATH = NODE_STATE_DIR + "/openclaw.json";

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mGatewayHostValue;
    private TextView mGatewayPortValue;
    private TextView mGatewayTlsValue;
    private TextView mGatewayTokenValue;
    private NodeGatewayConfig mGatewayConfig = NodeGatewayConfig.defaultConfig();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_node_runtime_mode, container, false);
        mStatusText = root.findViewById(R.id.status_text);
        mUptimeText = root.findViewById(R.id.uptime_text);
        mStatusIndicator = root.findViewById(R.id.status_indicator);
        mGatewayHostValue = root.findViewById(R.id.node_gateway_host_value);
        mGatewayPortValue = root.findViewById(R.id.node_gateway_port_value);
        mGatewayTlsValue = root.findViewById(R.id.node_gateway_tls_value);
        mGatewayTokenValue = root.findViewById(R.id.node_gateway_token_value);
        Button editGatewaySettingsButton = root.findViewById(R.id.btn_edit_node_gateway_settings);
        if (editGatewaySettingsButton != null) {
            editGatewaySettingsButton.setOnClickListener(v -> showGatewaySettingsDialog());
        }
        mGatewayConfig = loadGatewayConfig();
        renderGatewayConfig();
        return root;
    }

    @Override
    public void onRuntimeStatusUpdated(boolean isRunning, @Nullable String uptimeText) {
        if (mStatusText == null || mUptimeText == null || mStatusIndicator == null) {
            return;
        }
        mStatusText.setText(
            getString(isRunning ? getRuntimeRunningTextRes() : getRuntimeStoppedTextRes())
        );
        mStatusIndicator.setBackgroundResource(
            isRunning ? R.drawable.status_indicator_running : R.drawable.status_indicator_stopped
        );
        mUptimeText.setText("—");
    }

    @Override
    public void onRuntimeModeActivated() {
        mGatewayConfig = loadGatewayConfig();
        renderGatewayConfig();
    }

    @Override
    public void onRuntimeModeDeactivated() {
        // No-op.
    }

    @Override
    public void onServiceConnected(@Nullable BotDropService service) {
        // No-op.
    }

    @Override
    public void onServiceDisconnected() {
        // No-op.
    }

    @Override
    public int getRuntimeRunningTextRes() {
        return R.string.botdrop_node_running;
    }

    @Override
    public int getRuntimeStoppedTextRes() {
        return R.string.botdrop_node_stopped;
    }

    @Override
    public boolean isOpenclawWebUiEnabled() {
        return false;
    }

    @Override
    public boolean supportsGatewayErrorMonitoring() {
        return false;
    }

    @Override
    public void queryRuntimeStatus(@NonNull BotDropService service, @NonNull RuntimeStatusListener listener) {
        service.isNodeRunning(result -> {
            if (result == null) {
                listener.onStatusResult(false, null);
                return;
            }
            boolean running = result.success && "running".equals(result.stdout.trim());
            listener.onStatusResult(running, null);
        });
    }

    @Override
    public void onStartRuntime(@NonNull Context context, @NonNull BotDropService service,
                               @NonNull Button startButton, @NonNull Runnable onSuccess) {
        NodeGatewayConfig config = requireGatewayConfig(context);
        if (config == null) {
            return;
        }
        Toast.makeText(context, context.getText(R.string.botdrop_starting_node), Toast.LENGTH_SHORT).show();
        startButton.setEnabled(false);
        service.startNodeWithToken(config.token, result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_node_started), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_node_start_failed), Toast.LENGTH_SHORT).show();
            startButton.setEnabled(true);
        });
    }

    @Override
    public void onStopRuntime(@NonNull Context context, @NonNull BotDropService service,
                               @NonNull Button stopButton, @NonNull Runnable onSuccess) {
        stopButton.setEnabled(false);
        Toast.makeText(context, context.getText(R.string.botdrop_stopping_node), Toast.LENGTH_SHORT).show();
        service.stopNode(result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_node_stopped_toast), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_node_stop_failed), Toast.LENGTH_SHORT).show();
            stopButton.setEnabled(true);
        });
    }

    @Override
    public void onRestartRuntime(@NonNull Context context, @NonNull BotDropService service,
                                  @NonNull Button restartButton, @NonNull Runnable onSuccess) {
        NodeGatewayConfig config = requireGatewayConfig(context);
        if (config == null) {
            return;
        }
        restartButton.setEnabled(false);
        Toast.makeText(context, context.getText(R.string.botdrop_node_restarting), Toast.LENGTH_SHORT).show();
        service.restartNodeWithToken(config.token, result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_node_restarted), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_node_restart_failed), Toast.LENGTH_SHORT).show();
            restartButton.setEnabled(true);
        });
    }

    @Nullable
    private NodeGatewayConfig requireGatewayConfig(@NonNull Context context) {
        NodeGatewayConfig config = loadGatewayConfig();
        if (config == null || TextUtils.isEmpty(config.host)) {
            Toast.makeText(context, context.getText(R.string.botdrop_node_gateway_host_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        mGatewayConfig = config;
        renderGatewayConfig();
        return config;
    }

    private NodeGatewayConfig loadGatewayConfig() {
        NodeGatewayConfig fromNodeFile = loadGatewayConfigFromNodeFile();
        if (fromNodeFile != null && !TextUtils.isEmpty(fromNodeFile.host)) {
            return fromNodeFile;
        }
        return NodeGatewayConfig.defaultConfig();
    }

    @Nullable
    private NodeGatewayConfig loadGatewayConfigFromNodeFile() {
        try {
            File file = getNodeConfigFile();
            if (!file.exists()) {
                return null;
            }
            String json = readFileContent(file);
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            JSONObject root = new JSONObject(json);
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway == null) {
                return null;
            }
            String host = normalizeGatewayHost(gateway.optString("host", ""));
            int port = gateway.optInt("port", DEFAULT_GATEWAY_PORT);
            boolean tls = parseBooleanGatewayConfig(gateway.opt("tls"));
            Boolean fallbackTlsFromOpenclaw = loadGatewayTlsFromOpenclawConfig();
            if (Boolean.TRUE.equals(fallbackTlsFromOpenclaw)) {
                tls = true;
            }
            String token = loadGatewayTokenFromOpenclawConfig();
            JSONObject auth = gateway.optJSONObject("auth");
            if (TextUtils.isEmpty(token)) {
                token = auth == null ? "" : auth.optString("token", "");
                if (TextUtils.isEmpty(token)) {
                    token = gateway.optString("token", "");
                }
            }
            NodeGatewayConfig config = new NodeGatewayConfig(host, normalizeGatewayPort(port), tls, token == null ? "" : token.trim());
            return config;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private Boolean loadGatewayTlsFromOpenclawConfig() {
        try {
            File openclawFile = new File(OPENCLAW_CONFIG_PATH);
            if (!openclawFile.exists()) {
                return null;
            }
            String openclawJson = readFileContent(openclawFile);
            if (TextUtils.isEmpty(openclawJson)) {
                return null;
            }
            JSONObject root = new JSONObject(openclawJson);
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway == null) {
                return null;
            }
            JSONObject remote = gateway.optJSONObject("remote");
            if (remote == null) {
                return null;
            }
            String url = remote.optString("url", "").trim().toLowerCase(Locale.ROOT);
            if (url.startsWith("wss://")) {
                return true;
            }
            if (url.startsWith("ws://")) {
                return false;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private String loadGatewayTokenFromOpenclawConfig() {
        try {
            File openclawFile = new File(OPENCLAW_CONFIG_PATH);
            if (!openclawFile.exists()) {
                return "";
            }
            String openclawJson = readFileContent(openclawFile);
            if (TextUtils.isEmpty(openclawJson)) {
                return "";
            }
            JSONObject root = new JSONObject(openclawJson);
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway == null) {
                return "";
            }
            JSONObject remote = gateway.optJSONObject("remote");
            if (remote == null) {
                return "";
            }
            String token = remote.optString("token", "").trim();
            return token == null ? "" : token;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean parseBooleanGatewayConfig(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
            if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
                return true;
            }
            if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
                return false;
            }
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return false;
    }

    private void renderGatewayConfig() {
        if (mGatewayHostValue == null || mGatewayPortValue == null
            || mGatewayTlsValue == null || mGatewayTokenValue == null) {
            return;
        }
        String hostText = TextUtils.isEmpty(mGatewayConfig.host)
            ? getString(R.string.botdrop_node_gateway_token_not_set)
            : mGatewayConfig.host;
        String tlsText = mGatewayConfig.tls
            ? getString(R.string.botdrop_node_gateway_tls_enabled)
            : getString(R.string.botdrop_node_gateway_tls_disabled);
        String tokenText = maskToken(mGatewayConfig.token);
        mGatewayHostValue.setText(getString(R.string.botdrop_node_gateway_host_display, hostText));
        mGatewayPortValue.setText(getString(R.string.botdrop_node_gateway_port_display, mGatewayConfig.port));
        mGatewayTlsValue.setText(getString(R.string.botdrop_node_gateway_tls_display, tlsText));
        mGatewayTokenValue.setText(getString(R.string.botdrop_node_gateway_token_display, tokenText));
    }

    private void showGatewaySettingsDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_node_gateway_settings, null, false);
        EditText hostInput = dialogView.findViewById(R.id.node_settings_gateway_host_input);
        EditText tokenInput = dialogView.findViewById(R.id.node_settings_gateway_token_input);
        EditText portInput = dialogView.findViewById(R.id.node_settings_gateway_port_input);
        CheckBox tlsCheckbox = dialogView.findViewById(R.id.node_settings_gateway_tls_checkbox);
        hostInput.setText(mGatewayConfig.host);
        tokenInput.setText(mGatewayConfig.token);
        portInput.setText(String.valueOf(mGatewayConfig.port));
        tlsCheckbox.setChecked(mGatewayConfig.tls);

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(getString(R.string.botdrop_node_gateway_settings_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.botdrop_cancel), null)
            .setPositiveButton(getString(R.string.botdrop_save), null)
            .create();
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (saveButton == null) {
                return;
            }
            saveButton.setOnClickListener(v -> {
                NodeGatewayConfig newConfig = parseGatewayConfigFromDialog(context, hostInput, tokenInput, portInput, tlsCheckbox);
                if (newConfig == null) {
                    return;
                }
                if (!saveGatewayConfigToNodeFile(newConfig) || !saveOpenclawConfigToFile(newConfig)) {
                    Toast.makeText(context, context.getText(R.string.botdrop_node_gateway_save_failed), Toast.LENGTH_SHORT).show();
                    return;
                }
                mGatewayConfig = newConfig;
                renderGatewayConfig();
                Toast.makeText(context, context.getText(R.string.botdrop_node_gateway_saved), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    @Nullable
    private NodeGatewayConfig parseGatewayConfigFromDialog(
        @NonNull Context context,
        @NonNull EditText hostInput,
        @NonNull EditText tokenInput,
        @NonNull EditText portInput,
        @NonNull CheckBox tlsCheckbox
    ) {
        String host = normalizeGatewayHost(hostInput.getText() == null ? "" : hostInput.getText().toString());
        String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
        String portRaw = portInput.getText() == null ? "" : portInput.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(context, context.getText(R.string.botdrop_node_gateway_host_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        int port = DEFAULT_GATEWAY_PORT;
        if (!TextUtils.isEmpty(portRaw)) {
            try {
                port = Integer.parseInt(portRaw);
            } catch (NumberFormatException ignored) {
                port = -1;
            }
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(context, context.getText(R.string.botdrop_node_gateway_port_invalid), Toast.LENGTH_SHORT).show();
            return null;
        }
        return new NodeGatewayConfig(host, port, tlsCheckbox.isChecked(), token);
    }

    private boolean saveGatewayConfigToNodeFile(@NonNull NodeGatewayConfig config) {
        try {
            File configFile = new File(NODE_CONFIG_PATH);
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }

            JSONObject root = new JSONObject();
            if (configFile.exists()) {
                String raw = readFileContent(configFile);
                if (!TextUtils.isEmpty(raw)) {
                    root = new JSONObject(raw);
                }
            }

            if (!root.has("version") || root.optInt("version", 0) <= 0) {
                root.put("version", 1);
            }
            if (TextUtils.isEmpty(root.optString("nodeId", ""))) {
                root.put("nodeId", UUID.randomUUID().toString());
            }
            if (TextUtils.isEmpty(root.optString("displayName", ""))) {
                root.put("displayName", "BotDrop Node");
            }

            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway == null) {
                gateway = new JSONObject();
            }
            gateway.put("host", config.host);
            gateway.put("port", config.port);
            gateway.put("tls", config.tls);

            JSONObject auth = gateway.optJSONObject("auth");
            if (TextUtils.isEmpty(config.token)) {
                if (auth != null) {
                    auth.remove("token");
                    if (auth.length() == 0) {
                        gateway.remove("auth");
                    } else {
                        gateway.put("auth", auth);
                    }
                }
            } else {
                if (auth == null) {
                    auth = new JSONObject();
                }
                auth.put("token", config.token);
                gateway.put("auth", auth);
            }

            root.put("gateway", gateway);
            try (FileWriter writer = new FileWriter(configFile, false)) {
                writer.write(root.toString(2));
                writer.write("\n");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean saveOpenclawConfigToFile(@NonNull NodeGatewayConfig config) {
        try {
            File openclawConfigFile = new File(OPENCLAW_CONFIG_PATH);
            File parent = openclawConfigFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }

            JSONObject root = new JSONObject();
            if (openclawConfigFile.exists()) {
                String raw = readFileContent(openclawConfigFile);
                if (!TextUtils.isEmpty(raw)) {
                    JSONObject parsed = new JSONObject(raw);
                    if (parsed != null) {
                        root = parsed;
                    }
                }
            }

            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway == null) {
                gateway = new JSONObject();
            }
            String remoteUrl = (config.tls ? "wss" : "ws") + "://" + config.host + ":" + config.port;
            JSONObject remote = new JSONObject();
            remote.put("url", remoteUrl);
            remote.put("transport", "direct");
            if (!TextUtils.isEmpty(config.token)) {
                remote.put("token", config.token);
            }
            gateway.put("mode", "remote");
            gateway.put("remote", remote);
            gateway.put("auth", new JSONObject().put("mode", "token"));
            root.put("gateway", gateway);

            try (FileWriter writer = new FileWriter(openclawConfigFile, false)) {
                writer.write(root.toString(2));
                writer.write("\n");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    private File getNodeConfigFile() {
        return new File(NODE_CONFIG_PATH);
    }

    @NonNull
    private String readFileContent(@NonNull File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String normalizeGatewayHost(String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw.trim();
        if (host.isEmpty()) {
            return "";
        }
        if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        } else if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        }
        int slashIndex = host.indexOf('/');
        if (slashIndex >= 0) {
            host = host.substring(0, slashIndex);
        }
        return host.trim();
    }

    private int normalizeGatewayPort(int port) {
        if (port >= 1 && port <= 65535) {
            return port;
        }
        return DEFAULT_GATEWAY_PORT;
    }

    @NonNull
    private String maskToken(String token) {
        if (TextUtils.isEmpty(token)) {
            return getString(R.string.botdrop_node_gateway_token_not_set);
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 8) {
            return "********";
        }
        return trimmed.substring(0, 3) + "..." + trimmed.substring(trimmed.length() - 3);
    }

    private static final class NodeGatewayConfig {
        final String host;
        final int port;
        final boolean tls;
        final String token;

        NodeGatewayConfig(String host, int port, boolean tls, String token) {
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.token = token;
        }

        static NodeGatewayConfig defaultConfig() {
            return new NodeGatewayConfig("", DEFAULT_GATEWAY_PORT, false, "");
        }
    }
}
