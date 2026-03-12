package app.botdrop;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import app.botdrop.shizuku.ShizukuManager;
import app.botdrop.shizuku.ShizukuShellExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background service for executing BotDrop-related commands and managing gateway lifecycle.
 * Handles OpenClaw installation, configuration, and gateway control without showing terminal UI.
 */
public class BotDropService extends Service {

    private static final String LOG_TAG = "BotDropService";
    private static final int SHIZUKU_COMMAND_CONNECT_TIMEOUT_MS = 5000;
    private static final String SHIZUKU_BRIDGE_CONFIG_PATH =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/shizuku-bridge.json";
    private static final String BOTDROP_APT_SOURCE_LINE =
        "deb [trusted=yes] https://zhixianio.github.io/botdrop-packages/ stable main";
    private static final String BOTDROP_APT_SOURCES_LIST = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list";
    private static final String BOTDROP_APT_SOURCES_LIST_D = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list.d";
    private static final String BOTDROP_APT_LIST_FILE = BOTDROP_APT_SOURCES_LIST_D + "/botdrop.list";
    private static final long SHARP_INSTALL_RETRY_INTERVAL_MS = 10 * 60 * 1000L;
    private static final String BOTDROP_SHARED_ROOT = "/data/local/tmp/botdrop_tmp";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mSharpInstallExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mU2SetupExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ShizukuManager mShizukuManager = ShizukuManager.getInstance();
    private ShizukuShellExecutor mShizukuExecutor;
    private volatile boolean mUpdateInProgress = false;
    private final java.util.concurrent.atomic.AtomicBoolean mSharpInstallInProgress =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long mLastSharpCheckAttemptMs = 0L;

    private final ShizukuManager.StatusListener mShizukuStatusListener = status -> {
        if (mShizukuExecutor == null) {
            return;
        }
        if (status == ShizukuManager.Status.READY) {
            mShizukuExecutor.bind();
        } else {
            mShizukuExecutor.unbind();
        }
    };

    public class LocalBinder extends Binder {
        public BotDropService getService() {
            return BotDropService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logDebug(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mShizukuExecutor = new ShizukuShellExecutor(this);
        mShizukuManager.init(this);
        mShizukuManager.addStatusListener(mShizukuStatusListener);
        mShizukuExecutor.bind();
        Logger.logDebug(LOG_TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep the service alive even if no Activity is bound. GatewayMonitorService depends on
        // this service to execute gateway control commands while the app is backgrounded.
        return START_STICKY;
    }

    /**
     * Safely submit a task to an executor, catching RejectedExecutionException
     * that occurs when the executor has been shut down (e.g. after onDestroy).
     * Returns true if the task was accepted, false otherwise.
     */
    private boolean safeExecute(ExecutorService executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Logger.logWarn(LOG_TAG, "Executor rejected task (service shutting down): " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mShizukuManager.removeStatusListener(mShizukuStatusListener);
        if (mShizukuExecutor != null) {
            mShizukuExecutor.shutdown();
            mShizukuExecutor = null;
        }
        mExecutor.shutdown();
        mSharpInstallExecutor.shutdown();
        Logger.logDebug(LOG_TAG, "onDestroy");
    }

    /**
     * Result of a command execution
     */
    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    /**
     * Callback for async command execution
     */
    public interface CommandCallback {
        void onResult(CommandResult result);
    }

    /**
     * Callback for OpenClaw update progress
     */
    public interface UpdateProgressCallback {
        void onStepStart(String message);
        void onError(String error);
        void onComplete(String newVersion);
    }

    /**
     * Callback for installation progress
     */
    public interface InstallProgressCallback {
        void onStepStart(int step, String message);
        void onStepComplete(int step);
        void onError(String error);
        void onComplete();
    }

    /**
     * Execute a shell command in the Termux environment
     */
    public void executeCommand(String command, CommandCallback callback) {
        if (!safeExecute(mExecutor, () -> {
            CommandResult result = executeCommandSync(command);
            mHandler.post(() -> callback.onResult(result));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }

    /**
     * Execute a shell command in the Termux environment with custom timeout in seconds
     */
    public void executeCommand(String command, int timeoutSeconds, CommandCallback callback) {
        if (!safeExecute(mExecutor, () -> {
            CommandResult result = executeCommandSync(command, timeoutSeconds);
            mHandler.post(() -> callback.onResult(result));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }

    /**
     * Run ensureSharpInstalled synchronously on the u2 setup executor.
     * This also cleans up broken dpkg-perl/dpkg-scanpackages dependencies.
     */
    public void runEnsureSharpInstalled(CommandCallback callback) {
        if (!safeExecute(mU2SetupExecutor, () -> {
            ensureSharpInstalled();
            mHandler.post(() -> callback.onResult(new CommandResult(true, "done", "", 0)));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }

    /**
     * Execute a shell command on the dedicated u2 setup executor (does not block mExecutor).
     */
    public void executeU2SetupCommand(String command, CommandCallback callback) {
        if (!safeExecute(mU2SetupExecutor, () -> {
            CommandResult result = executeCommandSync(command);
            mHandler.post(() -> callback.onResult(result));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }

    /**
     * Execute a shell command on the dedicated u2 setup executor with custom timeout in seconds.
     */
    public void executeU2SetupCommand(String command, int timeoutSeconds, CommandCallback callback) {
        if (!safeExecute(mU2SetupExecutor, () -> {
            CommandResult result = executeCommandSync(command, timeoutSeconds);
            mHandler.post(() -> callback.onResult(result));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }

    /**
     * Execute a shell command synchronously with default 60-second timeout
     */
    private CommandResult executeCommandSync(String command) {
        return executeCommandSync(command, 60);
    }

    /**
     * Execute a shell command synchronously with configurable timeout
     */
    private CommandResult executeCommandSync(String command, int timeoutSeconds) {
        String safeCommand = command == null ? "" : command.trim();
        if (safeCommand.isEmpty()) {
            return new CommandResult(false, "", "Command is empty", -1);
        }

        int timeoutMs = timeoutSeconds > 0 ? timeoutSeconds * 1000 : 60000;

        if (mShizukuExecutor != null && shouldExecuteViaShizuku(safeCommand)) {
            ensureShizukuBridgeConfig();
            ShizukuShellExecutor.Result result = executeCommandViaShizuku(safeCommand, timeoutMs);
            if (result != null) {
                if (result.success) {
                    return new CommandResult(
                        result.success,
                        result.stdout == null ? "" : result.stdout,
                        result.stderr == null ? "" : result.stderr,
                        result.exitCode
                    );
                }

                if (isShizukuUnavailableResult(result)) {
                    Logger.logWarn(LOG_TAG, "Shizuku execution unavailable: " + result.stderr);
                    return new CommandResult(
                        false,
                        result.stdout == null ? "" : result.stdout,
                        formatShizukuUnavailableMessage(result.stderr),
                        result.exitCode
                    );
                }

                return new CommandResult(
                    result.success,
                    result.stdout == null ? "" : result.stdout,
                    result.stderr == null ? "" : result.stderr,
                    result.exitCode
                );
            } else {
                Logger.logWarn(LOG_TAG, "Shizuku execution unavailable: null result");
                return new CommandResult(
                    false,
                    "",
                    "Shizuku execution unavailable",
                    -1
                );
            }
        } else if (shouldExecuteViaShizuku(safeCommand)) {
            Logger.logWarn(LOG_TAG, shizukuUnavailableMessage());
            return new CommandResult(
                false,
                "",
                formatShizukuUnavailableMessage(null),
                -1
            );
        }

        return executeCommandViaLocal(safeCommand, timeoutSeconds);
    }

    private boolean shouldExecuteViaShizuku(String command) {
        // All shell commands run in the Termux environment (local).
        // Shizuku is only used for binder-level API operations, not shell execution.
        return false;
    }

    private ShizukuShellExecutor.Result executeCommandViaShizuku(String safeCommand, int timeoutMs) {
        if (mShizukuExecutor == null || mShizukuManager == null) {
            return null;
        }
        if (!mShizukuExecutor.isBound()) {
            Logger.logDebug(LOG_TAG, "Shizuku shell service not bound, waiting for connection");
            if (!mShizukuExecutor.waitForConnection(Math.min(timeoutMs, SHIZUKU_COMMAND_CONNECT_TIMEOUT_MS))) {
                Logger.logWarn(LOG_TAG, "waitForConnection timeout");
                return null;
            }
        }

        Logger.logInfo(LOG_TAG, "execute via shizuku bridge");
        return mShizukuExecutor.executeSync(safeCommand, timeoutMs);
    }

    private boolean isShizukuUnavailableResult(ShizukuShellExecutor.Result result) {
        if (result == null || result.stderr == null) {
            return true;
        }
        String stderr = result.stderr.toLowerCase(Locale.ROOT);
        return !result.success
            && (stderr.contains("shizuku execution unavailable")
            || stderr.contains("permission not granted")
            || stderr.contains("permission")
            || stderr.contains("binder")
            || stderr.contains("not available")
            || stderr.contains("process failed")
            || stderr.contains("security denied"));
    }

    private String formatShizukuUnavailableMessage(String stderrHint) {
        if (stderrHint != null && !stderrHint.trim().isEmpty()) {
            return "Shizuku unavailable: " + stderrHint.trim();
        }
        return shizukuUnavailableMessage();
    }

    private CommandResult executeCommandViaLocal(String safeCommand, int timeoutSeconds) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        Process process = null;
        java.io.File tmpScript = null;

        try {
            // Write command to temp script file (same approach as installOpenclaw —
            // ProcessBuilder with script files works reliably, bash -c does not)
            java.io.File tmpDir = new java.io.File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (!tmpDir.exists() && !tmpDir.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Failed to create temporary directory: " + tmpDir.getAbsolutePath());
            }
            tmpScript = new java.io.File(tmpDir, "cmd_" + System.currentTimeMillis() + ".sh");
            try (java.io.FileWriter fw = new java.io.FileWriter(tmpScript)) {
                fw.write("#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n");
                fw.write(safeCommand);
                fw.write("\n");
            }
            tmpScript.setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", tmpScript.getAbsolutePath());

            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            // Set SSL_CERT_FILE for Node.js fetch to find CA certificates
            pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
            // Ensure Node.js can resolve globally installed native addons (for sharp, etc.)
            pb.environment().put("NODE_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules");
            // Prefer IPv4 first; avoids long IPv6 connect stalls in Android/proot environments.
            pb.environment().put("NODE_OPTIONS", "--dns-result-order=ipv4first");

            pb.redirectErrorStream(true);

            Logger.logDebug(LOG_TAG, "Executing via local shell: " + safeCommand);
            process = pb.start();

            boolean isModelListCommand = safeCommand.contains("openclaw models list");
            int loggedLines = 0;
            final int MAX_VERBOSE_LINES = 20;

            // Read stdout (stderr is merged via redirectErrorStream)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    if (!isModelListCommand || loggedLines < MAX_VERBOSE_LINES) {
                        Logger.logVerbose(LOG_TAG, "stdout: " + line);
                        loggedLines++;
                    }
                }
            }

            // Wait with timeout to prevent hanging indefinitely
            boolean finished = waitForCompat(process, timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Command timeout after " + timeoutSeconds + " seconds");
                return new CommandResult(false, stdout.toString(),
                    "Command timeout after " + timeoutSeconds + " seconds", -1);
            }

            exitCode = process.exitValue();
            Logger.logDebug(LOG_TAG, "Command exited with code: " + exitCode);

            return new CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode);

        } catch (IOException | InterruptedException e) {
            Logger.logError(LOG_TAG, "Command execution failed: " + e.getMessage());
            if (process != null) {
                process.destroy();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(false, stdout.toString(), e.getMessage(), -1);
        } finally {
            if (tmpScript != null && tmpScript.exists()) {
                if (!tmpScript.delete()) {
                    Logger.logWarn(LOG_TAG, "Failed to delete temporary command script: " + tmpScript.getAbsolutePath());
                }
            }
        }
    }

    private void ensureShizukuBridgeConfig() {
        if (new File(SHIZUKU_BRIDGE_CONFIG_PATH).exists()) {
            return;
        }
        startShizukuBridgeService();
        if (!waitForShizukuBridgeConfig()) {
            Logger.logWarn(LOG_TAG, "token write fail, bridge config not created in time");
        }
    }

    private boolean waitForShizukuBridgeConfig() {
        File config = new File(SHIZUKU_BRIDGE_CONFIG_PATH);
        long timeoutAt = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < timeoutAt) {
            if (config.exists()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return config.exists();
    }

    private void startShizukuBridgeService() {
        Intent intent = new Intent(this, app.botdrop.shizuku.ShizukuBridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to start ShizukuBridgeService: " + e.getMessage());
        }
    }

    private String shizukuUnavailableMessage() {
        if (mShizukuManager == null) {
            return "Shizuku manager not initialized";
        }
        return "Embedded bridge unavailable: " + mShizukuManager.getStatus();
    }

    /**
     * Install OpenClaw by calling the standalone install.sh script.
     * Parses structured output lines for progress reporting:
     *   BOTDROP_STEP:N:START:message  → callback.onStepStart(N, message)
     *   BOTDROP_STEP:N:DONE           → callback.onStepComplete(N)
     *   BOTDROP_COMPLETE              → callback.onComplete()
     *   BOTDROP_ERROR:message         → callback.onError(message)
     *   BOTDROP_ALREADY_INSTALLED     → callback.onComplete()
     */
    public void installOpenclaw(InstallProgressCallback callback) {
        final String INSTALL_SCRIPT = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/botdrop/install.sh";

        if (!safeExecute(mExecutor, () -> {
            // Verify install script exists
            if (!new java.io.File(INSTALL_SCRIPT).exists()) {
                mHandler.post(() -> callback.onError(
                    "Install script not found at " + INSTALL_SCRIPT +
                    "\nBootstrap may be incomplete."
                ));
                return;
            }

            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                    INSTALL_SCRIPT
                );

                pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
                pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
                pb.environment().put("NODE_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules");
                pb.redirectErrorStream(true);

                Logger.logInfo(LOG_TAG, "Starting install via " + INSTALL_SCRIPT);
                process = pb.start();

                // Collect last lines of output for error reporting
                final java.util.LinkedList<String> recentLines = new java.util.LinkedList<>();
                final int MAX_RECENT = 20;

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Logger.logVerbose(LOG_TAG, "install.sh: " + line);
                        parseInstallOutput(line, callback);
                        recentLines.add(line);
                        if (recentLines.size() > MAX_RECENT) recentLines.remove(0);
                    }
                }

                boolean finished = waitForCompat(process, 300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    mHandler.post(() -> callback.onError("Installation timed out after 5 minutes"));
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    StringBuilder tail = new StringBuilder();
                    for (String l : recentLines) tail.append(l).append("\n");
                    String output = tail.toString();
                    mHandler.post(() -> callback.onError(
                        "Installation failed (exit code " + exitCode + ")\n\n" + output));
                }

            } catch (IOException | InterruptedException e) {
                Logger.logError(LOG_TAG, "Installation failed: " + e.getMessage());
                if (process != null) {
                    process.destroy();
                }
                String msg = e.getMessage();
                mHandler.post(() -> callback.onError("Installation error: " + msg));
            }
        })) {
            mHandler.post(() -> callback.onError("Service executor is shut down"));
        }
    }

    /**
     * Parse a single line of structured output from install.sh
     */
    private void parseInstallOutput(String line, InstallProgressCallback callback) {
        if (line.startsWith("BOTDROP_STEP:")) {
            // Format: BOTDROP_STEP:N:START:message or BOTDROP_STEP:N:DONE
            String[] parts = line.split(":", 4);
            if (parts.length >= 3) {
                try {
                    int step = Integer.parseInt(parts[1]);
                    String action = parts[2];
                    if ("START".equals(action)) {
                        String message = parts.length >= 4 ? parts[3] : "";
                        mHandler.post(() -> callback.onStepStart(step, message));
                    } else if ("DONE".equals(action)) {
                        mHandler.post(() -> callback.onStepComplete(step));
                    }
                } catch (NumberFormatException e) {
                    Logger.logWarn(LOG_TAG, "Invalid step number in: " + line);
                }
            }
        } else if ("BOTDROP_COMPLETE".equals(line.trim())) {
            Logger.logInfo(LOG_TAG, "Installation complete");
            mHandler.post(callback::onComplete);
        } else if ("BOTDROP_ALREADY_INSTALLED".equals(line.trim())) {
            Logger.logInfo(LOG_TAG, "Already installed, skipping");
            mHandler.post(callback::onComplete);
        } else if (line.startsWith("BOTDROP_ERROR:")) {
            String error = line.substring("BOTDROP_ERROR:".length());
            mHandler.post(() -> callback.onError(error));
        }
        // Other lines (npm output, etc.) are logged but not parsed
    }

    /**
     * Check if bootstrap (Node.js) is installed
     */
    public static boolean isBootstrapInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/node").exists();
    }

    /**
     * Check if OpenClaw is installed (check binary, not just module directory)
     */
    public static boolean isOpenclawInstalled() {
        // Check if the openclaw binary exists and is executable
        java.io.File binary = new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/openclaw");
        return binary.exists() && binary.canExecute();
    }

    /**
     * Get OpenClaw version (synchronously)
     */
    public static String getOpenclawVersion() {
        try {
            java.io.File packageJson = new java.io.File(
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/openclaw/package.json"
            );
            if (packageJson.exists()) {
                // Use try-with-resources to avoid resource leak
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(packageJson)
                )) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                    
                    // Use JSONObject for reliable parsing
                    org.json.JSONObject json = new org.json.JSONObject(content.toString());
                    return json.optString("version", null);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to get OpenClaw version: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if OpenClaw config exists
     */
    public static boolean isOpenclawConfigured() {
        return new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json").exists();
    }

    /**
     * Build a command with proper Termux environment.
     * Does NOT use termux-chroot — for non-openclaw commands only.
     */
    private String withTermuxEnv(String command) {
        return "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export BOTDROP_TERMUX_HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export BOTDROP_SHARED_ROOT=" + BOTDROP_SHARED_ROOT + " && " +
               "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + " && " +
               "export PATH=$PREFIX/bin:$PATH && " +
               "export TMPDIR=$PREFIX/tmp && " +
               "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem && " +
               "export NODE_PATH=$PREFIX/lib/node_modules && " +
               "export NODE_OPTIONS=--dns-result-order=ipv4first && " +
               command;
    }

    /**
     * Build an openclaw command wrapped in termux-chroot.
     * Required: Android kernel blocks os.networkInterfaces() which OpenClaw needs.
     * termux-chroot (proot) provides a virtual chroot that bypasses this limitation.
     */
    private String withTermuxChroot(String openclawArgs) {
        return "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export BOTDROP_TERMUX_HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export BOTDROP_SHARED_ROOT=" + BOTDROP_SHARED_ROOT + " && " +
               "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + " && " +
               "export PATH=$PREFIX/bin:$PATH && " +
               "export TMPDIR=$PREFIX/tmp && " +
               "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem && " +
               "export NODE_PATH=$PREFIX/lib/node_modules && " +
               "export NODE_OPTIONS=--dns-result-order=ipv4first && " +
               // `openclaw` is installed as a wrapper that already runs under `termux-chroot`.
               // Avoid nesting proot/termux-chroot, which can stall gateway startup for minutes.
               "openclaw " + openclawArgs;
    }

    private static final String GATEWAY_PID_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.pid";
    private static final String GATEWAY_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.log";

    public void startGateway(CommandCallback callback) {
        // Ensure legacy config keys are repaired right before starting the gateway.
        // This matters for in-place upgrades where users won't re-run channel setup.
        BotDropConfig.sanitizeLegacyConfig();

        // Deploy built-in skills (e.g. botdrop-u2) before starting gateway
        deployBuiltinSkills();

        mExecutor.execute(() -> {
            CommandResult startResult = executeGatewayStart();
            mHandler.post(() -> callback.onResult(startResult));
        });
    }

    /**
     * Deploy built-in skills from APK assets to OpenClaw workspace.
     * Copies assets/skills/* to configured workspace/skills and compatibility fallback paths.
     * Overwrites existing files to ensure latest version.
     */
    private void deployBuiltinSkills() {
        final String SKILLS_ASSET_PREFIX = "skills";

        try {
            String[] skillDirs = getAssets().list(SKILLS_ASSET_PREFIX);
            if (skillDirs == null || skillDirs.length == 0) {
                return;
            }

            java.util.List<String> targets = resolveBuiltinSkillsTargetRoots();
            if (targets.isEmpty()) {
                Logger.logWarn(LOG_TAG, "No valid target directories for built-in skills");
                return;
            }

            for (String targetRoot : targets) {
                for (String skillName : skillDirs) {
                    copyAssetDir(SKILLS_ASSET_PREFIX + "/" + skillName,
                        targetRoot + "/" + skillName);
                }
                Logger.logInfo(LOG_TAG, "Deployed built-in skills to: " + targetRoot);
            }
            Logger.logInfo(LOG_TAG, "Deployed " + skillDirs.length + " built-in skill(s) to " + targets.size()
                + " location(s)");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to deploy built-in skills: " + e.getMessage());
        }
    }

    private java.util.List<String> resolveBuiltinSkillsTargetRoots() {
        java.util.LinkedHashSet<String> targetRoots = new java.util.LinkedHashSet<>();

        String configuredWorkspace = readOpenclawWorkspace();
        if (!configuredWorkspace.isEmpty()) {
            targetRoots.add(configuredWorkspace + "/skills");
        }

        // Backward-compatible destinations
        targetRoots.add(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/workspace/skills");
        targetRoots.add(TermuxConstants.TERMUX_HOME_DIR_PATH + "/botdrop/skills");

        return new java.util.ArrayList<>(targetRoots);
    }

    private String readOpenclawWorkspace() {
        try {
            org.json.JSONObject config = BotDropConfig.readConfig();
            org.json.JSONObject agents = config.optJSONObject("agents");
            if (agents == null) {
                return "";
            }
            org.json.JSONObject defaults = agents.optJSONObject("defaults");
            if (defaults == null) {
                return "";
            }
            return normalizeWorkspacePath(defaults.optString("workspace", ""));
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to read openclaw workspace: " + e.getMessage());
            return "";
        }
    }

    private String normalizeWorkspacePath(String workspace) {
        if (workspace == null) {
            return "";
        }

        String value = workspace.trim();
        if (value.isEmpty()) {
            return "";
        }

        if ("~".equals(value)) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH;
        }

        if (value.startsWith("~/")) {
            value = TermuxConstants.TERMUX_HOME_DIR_PATH + value.substring(1);
        } else if (!value.startsWith("/")) {
            value = TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + value;
        }

        if (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }

    /**
     * Recursively copy an asset directory to the filesystem.
     */
    private void copyAssetDir(String assetPath, String destPath) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            // It's a file — copy it
            copyAssetFile(assetPath, destPath);
            return;
        }

        // It's a directory
        java.io.File destDir = new java.io.File(destPath);
        if (!destDir.exists() && !destDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Cannot create dir: " + destPath);
            return;
        }

        for (String child : children) {
            copyAssetDir(assetPath + "/" + child, destPath + "/" + child);
        }
    }

    /**
     * Copy a single asset file to the filesystem, overwriting if exists.
     */
    private void copyAssetFile(String assetPath, String destPath) throws IOException {
        java.io.File destFile = new java.io.File(destPath);
        java.io.File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (java.io.InputStream in = getAssets().open(assetPath);
             java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public void stopGateway(CommandCallback callback) {
        // PID files can be stale and the gateway may spawn children. Use best-effort cleanup to
        // prevent port 18789 conflicts and restart storms.
        String cmd =
            "PID=''\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then PID=$(cat " + GATEWAY_PID_FILE + " 2>/dev/null || true); fi\n" +
            "rm -f " + GATEWAY_PID_FILE + "\n" +
            "if [ -n \"$PID\" ]; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  pkill -TERM -P \"$PID\" 2>/dev/null || true\n" +
            "fi\n" +
            "pkill -TERM -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "sleep 1\n" +
            "pkill -9 -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "echo stopped\n";
        executeCommand(cmd, callback);
    }

    public void restartGateway(CommandCallback callback) {
        stopGateway(result -> {
            // Brief delay to let process fully terminate
            mHandler.postDelayed(() -> startGateway(callback), 1000);
        });
    }

    public void getGatewayStatus(CommandCallback callback) {
        isGatewayRunning(callback);
    }

    /**
     * Check if the gateway is currently running using PID file
     */
    public void isGatewayRunning(CommandCallback callback) {
        // Don't rely only on PID file (can be stale after crashes or upgrades).
        String cmd =
            "if [ -f " + GATEWAY_PID_FILE + " ] && kill -0 $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null; then\n" +
            "  echo running\n" +
            "  exit 0\n" +
            "fi\n" +
            "if pgrep -f \"openclaw.*gateway\" >/dev/null 2>&1; then\n" +
            "  echo running\n" +
            "else\n" +
            "  echo stopped\n" +
            "fi\n";
        executeCommand(cmd, callback);
    }

    /**
     * Get gateway uptime in a human-readable format
     */
    public void getGatewayUptime(CommandCallback callback) {
        String cmd = "if [ -f " + GATEWAY_PID_FILE + " ]; then " +
            "pid=$(cat " + GATEWAY_PID_FILE + "); " +
            "if kill -0 $pid 2>/dev/null; then " +
            "ps -p $pid -o etime= 2>/dev/null || echo '—'; " +
            "else echo '—'; fi; " +
            "else echo '—'; fi";
        executeCommand(cmd, callback);
    }

    /**
     * Whether an OpenClaw update is currently in progress.
     * Checked by GatewayMonitorService to suppress auto-restart during updates.
     */
    public boolean isUpdateInProgress() {
        return mUpdateInProgress;
    }

    /**
     * Update OpenClaw to the specified version from npm.
     * Stops the gateway, runs npm install, recreates the Android-specific wrapper,
     * and restarts the gateway. Reports progress via callback on the main thread.
     *
     * Must run on mExecutor — calls executeCommandSync directly to avoid deadlock
     * (the public stopGateway/startGateway methods also post to mExecutor).
     */
    public void updateOpenclaw(String targetVersion, UpdateProgressCallback callback) {
        final String packageVersion = normalizeOpenclawVersion(targetVersion);
        final java.util.concurrent.atomic.AtomicBoolean notified = new java.util.concurrent.atomic.AtomicBoolean(false);

        mExecutor.execute(() -> {
            mUpdateInProgress = true;
            try {
                // Step 1: Stop gateway
                Logger.logInfo(LOG_TAG, "Update: stopping gateway");
                notifyUpdateStep(callback, "Stopping gateway...");
                String stopCmd = buildStopGatewayScript();
                CommandResult stopResult = executeCommandSync(stopCmd, 60);
                if (!stopResult.success) {
                    // Non-fatal — gateway may not be running
                    Logger.logWarn(LOG_TAG, "Gateway stop returned non-zero: " + stopResult.stdout);
                }
                Thread.sleep(2000);

                // Step 2: npm install
                Logger.logInfo(LOG_TAG, "Update: running npm install");
                notifyUpdateStep(callback, "Installing update...");
                String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                String npmCmd =
                    "export PREFIX=" + prefix + "\n" +
                    "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "\n" +
                    "export PATH=$PREFIX/bin:$PATH\n" +
                    "export TMPDIR=$PREFIX/tmp\n" +
                    "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem\n" +
                    "export NODE_OPTIONS=--dns-result-order=ipv4first\n" +
                    OpenclawVersionUtils.buildNpmInstallCommand(packageVersion) + " 2>&1\n";
                CommandResult npmResult = executeCommandSync(npmCmd, 300);
                if (!npmResult.success) {
                    String tail = extractTail(npmResult.stdout, 15);
                    String error = "npm install failed (exit " + npmResult.exitCode + ")\n" + tail;
                    Logger.logError(LOG_TAG, "Update npm install failed: " + error);
                    notifyUpdateError(callback, notified, error);
                    return;
                }

                // Step 3: Recreate the Android-specific wrapper
                // npm install overwrites $PREFIX/bin/openclaw with its own shim which
                // doesn't work on Android/proot. We must recreate the custom wrapper.
                Logger.logInfo(LOG_TAG, "Update: recreating openclaw wrapper");
                notifyUpdateStep(callback, "Finalizing...");
                String binPrefix = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String wrapperCmd =
                    "export PREFIX=" + prefix + "\n" +
                    "cat > $PREFIX/bin/openclaw <<'BOTDROP_OPENCLAW_WRAPPER'\n" +
                    "#!" + binPrefix + "/bash\n" +
                    "PREFIX=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                    "ENTRY=\"\"\n" +
                    "for CANDIDATE in \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/dist/cli.js\" \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/bin/openclaw.js\" \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/dist/index.js\"; do\n" +
                    "  if [ -f \"$CANDIDATE\" ]; then\n" +
                    "    ENTRY=\"$CANDIDATE\"\n" +
                    "    break\n" +
                    "  fi\n" +
                    "done\n" +
                    "if [ -z \"$ENTRY\" ]; then\n" +
                    "  echo \"openclaw entrypoint not found under $PREFIX/lib/node_modules/openclaw\" >&2\n" +
                    "  exit 127\n" +
                    "fi\n" +
                    "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n" +
                    "export NODE_OPTIONS=\"--dns-result-order=ipv4first\"\n" +
                    "exec \"$PREFIX/bin/termux-chroot\" \"$PREFIX/bin/node\" \"$ENTRY\" \"$@\"\n" +
                    "BOTDROP_OPENCLAW_WRAPPER\n" +
                    "chmod 755 $PREFIX/bin/openclaw\n" +
                    "echo done\n";
                CommandResult wrapperResult = executeCommandSync(wrapperCmd, 30);
                if (!wrapperResult.success) {
                    Logger.logError(LOG_TAG, "Update: failed to recreate wrapper");
                    notifyUpdateError(callback, notified, "Failed to recreate openclaw wrapper");
                    return;
                }

                // Step 4: Re-apply koffi mock for Android compatibility
                Logger.logInfo(LOG_TAG, "Update: patching koffi");
                notifyUpdateStep(callback, "Patching Koffi...");
                String koffiPatchCmd = buildKoffiMockPatchScript();
                CommandResult koffiPatchResult = executeCommandSync(koffiPatchCmd, 30);
                if (!koffiPatchResult.success) {
                    Logger.logError(
                        LOG_TAG,
                        "Update: failed to patch koffi (exit " + koffiPatchResult.exitCode + "): "
                            + extractTail(koffiPatchResult.stdout, 20)
                    );
                    notifyUpdateError(
                        callback,
                        notified,
                        "Failed to apply Android-compatible koffi patch (npm update may be incomplete)"
                    );
                    return;
                }

                // Step 5: Start gateway
                Logger.logInfo(LOG_TAG, "Update: starting gateway");
                notifyUpdateStep(callback, "Starting gateway...");
                BotDropConfig.sanitizeLegacyConfig();
                CommandResult startResult = executeGatewayStart();

                String newVersion = getOpenclawVersion();
                String versionStr = newVersion != null ? newVersion : "unknown";

                if (startResult.success) {
                    Logger.logInfo(LOG_TAG, "Update complete, new version: " + versionStr);
                    notifyUpdateComplete(callback, notified, versionStr);
                } else {
                    Logger.logWarn(LOG_TAG, "Update complete but gateway restart failed");
                    String tail = extractTail(startResult.stdout, 20);
                    notifyUpdateError(callback, notified,
                        "Gateway restart failed (exit " + startResult.exitCode + "):\n" + tail);
                }

            } catch (InterruptedException e) {
                Logger.logError(LOG_TAG, "Update interrupted: " + e.getMessage());
                notifyUpdateError(callback, notified, "Update interrupted");
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Update failed: " + e.getMessage());
                notifyUpdateError(callback, notified, "Update failed: " + e.getMessage());
            } finally {
                mUpdateInProgress = false;
            }
        });
    }

    private CommandResult executeGatewayStart() {
        scheduleSilentSharpInstallationCheck();
        return executeCommandSync(buildStartGatewayScript());
    }

    /**
     * Schedule a background sharp installation check/install. This method must be fast and must not
     * block gateway start/restart flows.
     */
    private void scheduleSilentSharpInstallationCheck() {
        long now = System.currentTimeMillis();
        long lastAttempt = mLastSharpCheckAttemptMs;
        if (now - lastAttempt < SHARP_INSTALL_RETRY_INTERVAL_MS) {
            return;
        }
        if (!mSharpInstallInProgress.compareAndSet(false, true)) {
            return;
        }

        mLastSharpCheckAttemptMs = now;
        mSharpInstallExecutor.execute(() -> {
            try {
                ensureSharpInstalled();
            } finally {
                mSharpInstallInProgress.set(false);
            }
        });
    }

    /**
     * Build the stop-gateway shell script (same logic as stopGateway but returns the string
     * instead of executing it, so it can be used from within updateOpenclaw on mExecutor).
     */
    private String buildStopGatewayScript() {
        return "PID=''\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then PID=$(cat " + GATEWAY_PID_FILE + " 2>/dev/null || true); fi\n" +
            "rm -f " + GATEWAY_PID_FILE + "\n" +
            "if [ -n \"$PID\" ]; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  pkill -TERM -P \"$PID\" 2>/dev/null || true\n" +
            "fi\n" +
            "pkill -TERM -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "sleep 1\n" +
            "pkill -9 -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "echo stopped\n";
    }

    private String buildKoffiMockPatchScript() {
        return "KOFFI_DIR=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/openclaw/node_modules/koffi\"\n" +
            "KOFFI_INDEX=\"$KOFFI_DIR/index.js\"\n" +
            "if [ -d \"$KOFFI_DIR\" ] && [ -f \"$KOFFI_INDEX\" ]; then\n" +
            "  if [ ! -f \"$KOFFI_INDEX.orig\" ]; then\n" +
            "    cp \"$KOFFI_INDEX\" \"$KOFFI_INDEX.orig\"\n" +
            "  fi\n" +
            "  cat > \"$KOFFI_INDEX\" <<'BOTDROP_KOFFI_MOCK'\n" +
            "module.exports = {\n" +
            "  load() {\n" +
            "    throw new Error(\"koffi native module not available on this platform\");\n" +
            "  }\n" +
            "};\n" +
            "BOTDROP_KOFFI_MOCK\n" +
            "fi\n";
    }

    private String normalizeOpenclawVersion(String targetVersion) {
        if (targetVersion == null) {
            return "openclaw@latest";
        }

        String trimmed = targetVersion.trim();
        if (trimmed.isEmpty() || "openclaw".equals(trimmed) || "latest".equals(trimmed)) {
            return "openclaw@latest";
        }

        if (trimmed.startsWith("openclaw@")) {
            return trimmed;
        }
        return "openclaw@" + trimmed;
    }

    private void notifyUpdateStep(UpdateProgressCallback callback, String message) {
        if (callback == null) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onStepStart(message);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update progress callback failed: " + e.getMessage());
            }
        });
    }

    private void notifyUpdateError(
        UpdateProgressCallback callback,
        java.util.concurrent.atomic.AtomicBoolean notified,
        String error
    ) {
        if (callback == null || !notified.compareAndSet(false, true)) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onError(error);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update error callback failed: " + e.getMessage());
            }
        });
    }

    private void notifyUpdateComplete(
        UpdateProgressCallback callback,
        java.util.concurrent.atomic.AtomicBoolean notified,
        String version
    ) {
        if (callback == null || !notified.compareAndSet(false, true)) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onComplete(version);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update complete callback failed: " + e.getMessage());
            }
        });
    }

    /**
     * Ensure sharp native addon is installed (idempotent, non-fatal).
     * For upgrade users whose install.sh already ran before sharp support was added.
     * Must be called on mExecutor thread (not the main thread).
     */
    private void ensureSharpInstalled() {
        String cmd =
            buildBotDropAptSourceScript() +
            buildTermuxGcryptHardwareAccelerationCheckCommand() +
            "SHARP_CHECK=$(node -e 'try { require(\"sharp\"); process.exit(0); } catch (e) { console.error(e.message); process.exit(1); }' 2>&1)\n" +
            "SHARP_EXIT=$?\n" +
            "if [ $SHARP_EXIT -eq 0 ]; then\n" +
            "    echo 'sharp already installed'\n" +
            "    exit 0\n" +
            "fi\n" +
            "if [ -n \"$SHARP_CHECK\" ]; then\n" +
            "    echo \"sharp is installed but not runnable: $SHARP_CHECK\"\n" +
            "fi\n" +
            "echo 'Installing sharp native addon...'\n" +
            "# APT sources are already forced to BotDrop-only above.\n" +
            "# Remove conflicting packages that depend on clang from Termux main repo\n" +
            "if dpkg -s dpkg-scanpackages >/dev/null 2>&1; then\n" +
            "    dpkg -r dpkg-scanpackages 2>/dev/null || true\n" +
            "fi\n" +
            "if dpkg -s dpkg-perl >/dev/null 2>&1; then\n" +
            "    dpkg -r dpkg-perl 2>/dev/null || true\n" +
            "fi\n" +
            "# Update only the BotDrop source\n" +
            "APT_UPDATE_OUTPUT=$(apt update -o Dir::Etc::sourcelist=\"$PREFIX/etc/apt/sources.list.d/botdrop.list\" -o Dir::Etc::sourceparts=\"-\" 2>&1)\n" +
            "APT_UPDATE_EXIT=$?\n" +
            "if [ $APT_UPDATE_EXIT -ne 0 ]; then\n" +
            "    echo \"apt update failed (exit $APT_UPDATE_EXIT): $APT_UPDATE_OUTPUT\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "# Install native addon via apt\n" +
            "APT_OUTPUT=$(apt install -y -o Dir::Etc::sourcelist=\"$PREFIX/etc/apt/sources.list.d/botdrop.list\" -o Dir::Etc::sourceparts=\"-\" sharp-node-addon 2>&1)\n" +
            "APT_EXIT=$?\n" +
            "if [ $APT_EXIT -ne 0 ]; then\n" +
            "    echo \"sharp-node-addon install failed (exit $APT_EXIT): $APT_OUTPUT\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "# Install sharp npm package (--ignore-scripts since native addon is from apt)\n" +
            "NPM_OUTPUT=$(npm install -g sharp@0.34.5 --ignore-scripts 2>&1)\n" +
            "NPM_EXIT=$?\n" +
            "if [ $NPM_EXIT -ne 0 ]; then\n" +
            "    echo \"sharp npm install failed (exit $NPM_EXIT): $NPM_OUTPUT\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "# Final verify that sharp can be imported from Node.js after install.\n" +
            "SHARP_VERIFY=$(node -e 'try { require(\"sharp\"); process.exit(0); } catch (e) { console.error(e.message); process.exit(1); }' 2>&1)\n" +
            "SHARP_VERIFY_EXIT=$?\n" +
            "if [ $SHARP_VERIFY_EXIT -ne 0 ]; then\n" +
            "    echo \"sharp verification failed (after install): $SHARP_VERIFY\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "echo 'sharp installed successfully'\n" +
            "# Add NODE_PATH to botdrop-env.sh if missing\n" +
            "ENV_FILE=$PREFIX/etc/profile.d/botdrop-env.sh\n" +
            "if [ -f \"$ENV_FILE\" ] && ! grep -q 'NODE_PATH' \"$ENV_FILE\"; then\n" +
            "    echo 'export NODE_PATH=$PREFIX/lib/node_modules' >> \"$ENV_FILE\"\n" +
            "fi\n";

        CommandResult result = executeCommandSync(cmd, 120);
        if (result.success) {
            Logger.logInfo(LOG_TAG, "ensureSharpInstalled: " + result.stdout.trim());
        } else {
            Logger.logWarn(LOG_TAG, "ensureSharpInstalled failed (non-fatal): " + result.stdout.trim());
        }
    }

    private String buildBotDropAptSourceScript() {
        return
            "mkdir -p " + BOTDROP_APT_SOURCES_LIST_D + "\n" +
            "printf '%s\\n' '" + BOTDROP_APT_SOURCE_LINE + "' > " + BOTDROP_APT_LIST_FILE + "\n" +
            "printf '%s\\n' '" + BOTDROP_APT_SOURCE_LINE + "' > " + BOTDROP_APT_SOURCES_LIST + "\n" +
            "for f in " + BOTDROP_APT_SOURCES_LIST_D + "/*.list; do\n" +
            "    if [ -f \"$f\" ] && [ \"$f\" != \"" + BOTDROP_APT_LIST_FILE + "\" ]; then\n" +
            "        rm -f \"$f\"\n" +
            "    fi\n" +
            "done\n";
    }

    private String buildTermuxGcryptHardwareAccelerationCheckCommand() {
        return "export PREFIX=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\"\n"
            + "export PATH=\"$PREFIX/bin:$PATH\"\n"
            + "HWF_DIR=\"$PREFIX/etc/gcrypt\"\n"
            + "HWF_DENY=\"$HWF_DIR/hwf.deny\"\n"
            + "if ! command -v python3 >/dev/null 2>&1; then\n"
            + "  echo \"[check-gcrypt] python3 not found\"\n"
            + "else\n"
            + "  if python3 - <<'PY'\n"
            + "import ctypes\n"
            + "gc = ctypes.CDLL('libgcrypt.so')\n"
            + "gc.gcry_check_version(None)\n"
            + "for algo, name in [(1, 'MD5'), (2, 'SHA1'), (8, 'SHA256'), (10, 'SHA512')]:\n"
            + "    hd = ctypes.c_void_p()\n"
            + "    gc.gcry_md_open(ctypes.byref(hd), algo, 0)\n"
            + "    data = b'A' * 65536\n"
            + "    for _ in range(16):\n"
            + "        gc.gcry_md_write(hd, data, len(data))\n"
            + "    print(f'{name}: write 1MB OK')\n"
            + "PY\n"
            + "  then\n"
            + "    echo \"[check-gcrypt] gcrypt hardware acceleration test passed\"\n"
            + "  else\n"
            + "    echo \"[check-gcrypt] gcrypt hardware acceleration test failed\"\n"
            + "    mkdir -p \"$HWF_DIR\"\n"
            + "    echo \"all\" > \"$HWF_DENY\"\n"
            + "    echo \"[check-gcrypt] hwf.deny written: $HWF_DENY\"\n"
            + "  fi\n"
            + "fi\n"
            + "\n";
    }

    /**
     * Build the start-gateway shell script (same logic as startGateway but returns the string
     * instead of executing it, so it can be used from within updateOpenclaw on mExecutor).
     */
    private String buildStartGatewayScript() {
        String logDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
        String debugLog = logDir + "/gateway-debug.log";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        return "mkdir -p " + logDir + "\n" +
            "exec 2>" + debugLog + "\n" +
            "set -x\n" +
            "echo \"date: $(date)\" >&2\n" +
            "echo \"id: $(id)\" >&2\n" +
            "echo \"PATH=$PATH\" >&2\n" +
            "pgrep -x sshd || sshd || true\n" +
            "pkill -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then\n" +
            "  kill $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  sleep 1\n" +
            "fi\n" +
            "sleep 1\n" +
            "echo '' > " + GATEWAY_LOG_FILE + "\n" +
            "export BOTDROP_TERMUX_HOME=" + home + "\n" +
            "export BOTDROP_SHARED_ROOT=" + BOTDROP_SHARED_ROOT + "\n" +
            "export HOME=" + home + "\n" +
            "export PREFIX=" + prefix + "\n" +
            "export PATH=$PREFIX/bin:$PATH\n" +
            "export TMPDIR=$PREFIX/tmp\n" +
            "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem\n" +
            "export NODE_PATH=$PREFIX/lib/node_modules\n" +
            "export NODE_OPTIONS=--dns-result-order=ipv4first\n" +
            "echo \"=== Environment before chroot ===\" >&2\n" +
            "echo \"SSL_CERT_FILE=$SSL_CERT_FILE\" >&2\n" +
            "echo \"NODE_PATH=$NODE_PATH\" >&2\n" +
            "echo \"NODE_OPTIONS=$NODE_OPTIONS\" >&2\n" +
            "echo \"Testing cert file access:\" >&2\n" +
            "ls -lh $PREFIX/etc/tls/cert.pem >&2 || echo \"cert.pem not found!\" >&2\n" +
            "openclaw gateway run --force >> " + GATEWAY_LOG_FILE + " 2>&1 &\n" +
            "GW_PID=$!\n" +
            "echo $GW_PID > " + GATEWAY_PID_FILE + "\n" +
            "echo \"gateway pid: $GW_PID\" >&2\n" +
            "sleep 3\n" +
            "if kill -0 $GW_PID 2>/dev/null; then\n" +
            "  echo started\n" +
            "else\n" +
            "  echo \"gateway died, log:\" >&2\n" +
            "  cat " + GATEWAY_LOG_FILE + " >&2\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  cat " + GATEWAY_LOG_FILE + "\n" +
            "  echo '---'\n" +
            "  cat " + debugLog + "\n" +
            "  exit 1\n" +
            "fi\n";
    }

    /**
     * Compatibility wrapper for Process.waitFor(long, TimeUnit) which is only
     * available on API 26+. On older devices, polls with Thread.sleep.
     */
    private static boolean waitForCompat(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeout, unit);
        }
        // Polling fallback for API < 26
        long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                process.exitValue();
                return true; // process has exited
            } catch (IllegalThreadStateException e) {
                // still running
            }
            Thread.sleep(500);
        }
        return false;
    }

    /**
     * Extract the last N lines from a string for error reporting.
     */
    private static String extractTail(String text, int maxLines) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

}
