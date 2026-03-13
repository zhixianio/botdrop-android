package app.botdrop;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.shizuku.ShizukuStatusActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import app.botdrop.shizuku.ShizukuShellExecutor;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Arrays;

import moe.shizuku.manager.MainActivity;
import rikka.shizuku.Shizuku;

/**
 * Dedicated panel for automation related controls (currently Shizuku helpers).
 */
public class AutomationPanelActivity extends Activity {

    private static final String LOG_TAG = "AutomationPanelActivity";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 2001;
    private static final String OPENCLAW_GATEWAY_PRECHECK_COMMAND = "openclaw --version";
    private static final long SERVICE_STATUS_TIMEOUT_MS = 1500;
    private static final long SHIZUKU_SHELL_COMMAND_TIMEOUT_MS = 30000;
    private static final long SHIZUKU_SHELL_CONNECT_TIMEOUT_MS = 5000;
    private static final long SERVICE_REFRESH_DELAY_MS = 1000;
    private static final long STATUS_POLL_INTERVAL_MS = 3000;
    private static final String SHIZUKU_BRIDGE_STATUS_URL = "http://127.0.0.1:18790/shizuku/status";
    private static final String U2_PING_URL = "http://127.0.0.1:9008/ping";
    private static final String SHIZUKU_BRIDGE_CONFIG_PATH =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/shizuku-bridge.json";
    private static final String U2_AUTOMATOR_APT_PACKAGE = "python-uiautomator2-botdrop";
    // Shizuku shell runs as `shell` user and cannot reliably write app-internal dirs.
    private static final String U2_PID_FILE = "/data/local/tmp/u2.pid";
    private static final String U2_RUNTIME_JAR_PATH = "/data/local/tmp/u2.jar";
    private static final String U2_ASSET_PATH = "u2.jar";
    private static final long U2_PREPARE_TIMEOUT_MS = 180000;
    private static final String U2_IME_APK_URL =
        "https://github.com/openatx/android-uiautomator-server/releases/latest/download/app-uiautomator.apk";
    private static final String U2_IME_PACKAGE = "com.github.uiautomator";
    private static final String U2_IME_COMPONENT = U2_IME_PACKAGE + "/.AdbKeyboard";

    private ImageButton mBackButton;
    private Button mOpenShizukuButton;
    private Button mShizukuPermissionButton;
    private TextView mStatusText;
    private TextView mShizukuBridgeStatusText;
    private TextView mU2StatusText;
    private Button mStartU2ServiceButton;
    private Button mStopU2ServiceButton;
    private StepProgressDialog mU2StartProgressDialog;

    private BotDropService mBotDropService;
    private ShizukuShellExecutor mShizukuShellExecutor;
    private boolean mBound;
    private volatile boolean mU2SetupInProgress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStatusPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isUiAvailable()) {
                return;
            }
            refreshAutomationServiceStatuses();
            mHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS);
        }
    };
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener mShizukuPermissionResultListener =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) {
                        return;
                    }

                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    Logger.logInfo(
                        LOG_TAG,
                        "Shizuku permission result callback: requestCode="
                                + requestCode
                                + ", grantResult="
                                + grantResult
                                + ", granted="
                                + granted
                    );
                    runOnUiThread(() -> {
                        runShizukuPermissionButtonStateBusy(false, null);
                        mStatusText.setText(
                                granted
                                        ? getString(R.string.botdrop_shizuku_permission_granted)
                                        : getString(R.string.botdrop_shizuku_permission_denied)
                        );
                        Toast.makeText(
                                AutomationPanelActivity.this,
                                granted
                                        ? getString(R.string.botdrop_shizuku_permission_granted)
                                        : getString(R.string.botdrop_shizuku_permission_denied),
                                Toast.LENGTH_SHORT
                        ).show();
                        if (granted) {
                            runShizukuGatewayPrecheck();
                        } else {
                            Logger.logWarn(
                                    LOG_TAG,
                                    "Shizuku permission request denied: requestCode="
                                            + requestCode
                                            + ", package="
                                            + getPackageName()
                            );
                        }
                    });
                    Shizuku.removeRequestPermissionResultListener(this);
                }
            };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mBotDropService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
            refreshShizukuPermissionState();
            refreshAutomationServiceStatuses();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBotDropService = null;
            mBound = false;
            setU2ServiceButtonsState(false, false);
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shizuku_automation);

        mBackButton = findViewById(R.id.btn_back_to_dashboard);
        mOpenShizukuButton = findViewById(R.id.btn_open_shizuku);
        mShizukuPermissionButton = findViewById(R.id.btn_request_shizuku_permission);
        mStatusText = findViewById(R.id.automation_status_text);
        mShizukuBridgeStatusText = findViewById(R.id.automation_shizuku_bridge_status_text);
        mU2StatusText = findViewById(R.id.automation_u2_status_text);
        mStartU2ServiceButton = findViewById(R.id.btn_start_u2_service);
        mStopU2ServiceButton = findViewById(R.id.btn_stop_u2_service);

        mBackButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_back_tap");
            finish();
        });
        mOpenShizukuButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_open_shizuku_tap");
            openShizukuStatus();
        });
        mShizukuPermissionButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_permission_tap");
            diagnoseShizukuPermission();
        });
        mStartU2ServiceButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_u2_start_tap");
            startU2Service();
        });
        mStopU2ServiceButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_u2_stop_tap");
            stopU2Service();
        });

        bindBotDropService();
        initShizukuShellExecutor();
        refreshShizukuPermissionState();
        refreshAutomationServiceStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AnalyticsManager.logScreen(this, "automation_panel", "AutomationPanelActivity");
        refreshShizukuPermissionState();
        startStatusPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatusPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStatusPolling();
        Shizuku.removeRequestPermissionResultListener(mShizukuPermissionResultListener);
        if (mU2StartProgressDialog != null && mU2StartProgressDialog.isShowing()) {
            mU2StartProgressDialog.dismiss();
        }
        mU2StartProgressDialog = null;
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        if (mShizukuShellExecutor != null) {
            mShizukuShellExecutor.shutdown();
            mShizukuShellExecutor = null;
        }
        mBotDropService = null;
    }

    private void initShizukuShellExecutor() {
        mShizukuShellExecutor = new ShizukuShellExecutor(this);
        mShizukuShellExecutor.bind();
    }

    private void bindBotDropService() {
        Intent intent = new Intent(this, BotDropService.class);
        try {
            bindService(intent, mConnection, BIND_AUTO_CREATE);
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to bind BotDropService: " + e.getMessage());
        }
    }

    private void startStatusPolling() {
        mHandler.removeCallbacks(mStatusPollRunnable);
        mHandler.post(mStatusPollRunnable);
    }

    private void stopStatusPolling() {
        mHandler.removeCallbacks(mStatusPollRunnable);
    }

    private void openShizukuStatus() {
        if (startOfficialShizukuHome()) {
            return;
        }

        Intent intent = new Intent(this, ShizukuStatusActivity.class);
        intent.putExtra(ShizukuStatusActivity.EXTRA_AUTO_START, true);
        intent.putExtra(ShizukuStatusActivity.EXTRA_AUTO_REQUEST_PERMISSION, true);
        startActivity(intent);
    }

    private void diagnoseShizukuPermission() {
        Logger.logInfo(LOG_TAG, "diagnoseShizukuPermission: binder ready=" + Shizuku.pingBinder());
        if (!Shizuku.pingBinder()) {
            mStatusText.setText(getString(R.string.botdrop_shizuku_binder_not_ready));
            Toast.makeText(this, getString(R.string.botdrop_shizuku_binder_not_ready), Toast.LENGTH_SHORT).show();
            return;
        }

        int permission = PackageManager.PERMISSION_DENIED;
        try {
            permission = Shizuku.checkSelfPermission();
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "checkSelfPermission failed: " + tr.getMessage());
        }

        Logger.logInfo(
                LOG_TAG,
                "Shizuku checkSelfPermission="
                        + (permission == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
                        + ", uid=" + android.os.Process.myUid()
                        + ", serverUid=" + Shizuku.getUid()
        );

        if (permission == PackageManager.PERMISSION_GRANTED) {
            mStatusText.setText(getString(R.string.botdrop_shizuku_permission_already_granted));
            Toast.makeText(this, getString(R.string.botdrop_shizuku_permission_already_granted), Toast.LENGTH_SHORT).show();
            runShizukuGatewayPrecheck();
            return;
        }

        runShizukuPermissionButtonStateBusy(true, getString(R.string.botdrop_waiting));
        mStatusText.setText(getString(R.string.botdrop_shizuku_requesting_permission));
        Shizuku.removeRequestPermissionResultListener(mShizukuPermissionResultListener);
        Shizuku.addRequestPermissionResultListener(mShizukuPermissionResultListener);
        Toast.makeText(this, getString(R.string.botdrop_shizuku_requesting_permission), Toast.LENGTH_SHORT).show();
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
    }

    private void runShizukuGatewayPrecheck() {
        if (mBotDropService == null) {
            Logger.logWarn(LOG_TAG, "Shizuku precheck skipped: BotDropService not connected");
            Toast.makeText(this, getString(R.string.botdrop_shizuku_precheck_service_not_connected), Toast.LENGTH_SHORT).show();
            runShizukuPermissionButtonStateBusy(false, null);
            mStatusText.setText(getString(R.string.botdrop_shizuku_precheck_service_not_connected));
            return;
        }

        Logger.logInfo(LOG_TAG, "Running Shizuku precheck command: " + OPENCLAW_GATEWAY_PRECHECK_COMMAND);
        runShizukuPermissionButtonStateBusy(true, getString(R.string.botdrop_checking));
        mStatusText.setText(getString(R.string.botdrop_shizuku_precheck_running));

        mBotDropService.executeCommand(OPENCLAW_GATEWAY_PRECHECK_COMMAND, result -> {
            runOnUiThread(() -> {
                runShizukuPermissionButtonStateBusy(false, null);
                if (result == null) {
                    Logger.logWarn(LOG_TAG, "Shizuku precheck returned null result");
                    mStatusText.setText(getString(R.string.botdrop_shizuku_precheck_no_result));
                    Toast.makeText(AutomationPanelActivity.this, getString(R.string.botdrop_shizuku_precheck_no_result), Toast.LENGTH_SHORT).show();
                    return;
                }

                Logger.logInfo(
                        LOG_TAG,
                        "Shizuku precheck exit=" + result.exitCode + ", success=" + result.success
                                + ", stdoutLen=" + (result.stdout == null ? 0 : result.stdout.length())
                                + ", stderrLen=" + (result.stderr == null ? 0 : result.stderr.length())
                );

                if (result.success) {
                    mStatusText.setText(getString(R.string.botdrop_shizuku_precheck_passed));
                    Toast.makeText(AutomationPanelActivity.this, getString(R.string.botdrop_shizuku_precheck_passed), Toast.LENGTH_SHORT).show();
                } else {
                    String reason = TextUtils.isEmpty(result.stderr) ? result.stdout : result.stderr;
                    String shownReason = TextUtils.isEmpty(reason) ? getString(R.string.botdrop_unknown) : reason;
                    mStatusText.setText(
                            getString(R.string.botdrop_shizuku_precheck_failed, shownReason)
                    );
                    Toast.makeText(
                            AutomationPanelActivity.this,
                            getString(R.string.botdrop_shizuku_precheck_failed, shownReason),
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    private void refreshShizukuPermissionState() {
        if (mStatusText == null) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                mStatusText.setText(getString(R.string.botdrop_shizuku_binder_not_ready));
                return;
            }
            int permission = Shizuku.checkSelfPermission();
            if (permission == PackageManager.PERMISSION_GRANTED) {
                mStatusText.setText(getString(R.string.botdrop_shizuku_permission_granted));
            } else {
                mStatusText.setText(getString(R.string.botdrop_shizuku_permission_not_granted));
            }
        } catch (Throwable tr) {
            mStatusText.setText(getString(R.string.botdrop_shizuku_status_unavailable));
            Logger.logWarn(LOG_TAG, "refreshShizukuPermissionState failed: " + tr.getMessage());
        }
    }

    private void refreshAutomationServiceStatuses() {
        checkShizukuBridgeStatus();
        checkU2ServiceStatus();
        if (!mBound) {
            setU2ServiceButtonsState(false, false);
        }
    }

    private void checkShizukuBridgeStatus() {
        if (mShizukuBridgeStatusText == null) {
            return;
        }

        if (TextUtils.isEmpty(mShizukuBridgeStatusText.getText())) {
            mShizukuBridgeStatusText.setText(getString(R.string.botdrop_shizuku_bridge_status_checking));
        }

        new Thread(() -> {
            String token = readShizukuBridgeToken();
            final ServiceStateResult result;

            if (TextUtils.isEmpty(token)) {
                result = new ServiceStateResult(false, getString(R.string.botdrop_shizuku_bridge_status_not_configured));
            } else {
                result = queryHttpEndpoint(SHIZUKU_BRIDGE_STATUS_URL, "Bearer " + token);
            }

            if (!isUiAvailable()) {
                return;
            }

            runOnUiThread(() -> {
                if (!isUiAvailable() || mShizukuBridgeStatusText == null) {
                    return;
                }

                if (result.running) {
                    mShizukuBridgeStatusText.setText(getString(R.string.botdrop_shizuku_bridge_status_running));
                } else if (TextUtils.isEmpty(result.message)) {
                    mShizukuBridgeStatusText.setText(getString(R.string.botdrop_shizuku_bridge_status_stopped));
                } else {
                    mShizukuBridgeStatusText.setText(
                        getString(R.string.botdrop_shizuku_bridge_status_stopped_with_reason, result.message)
                    );
                }
            });
        }).start();
    }

    private void checkU2ServiceStatus() {
        if (mU2StatusText == null) {
            return;
        }

        if (TextUtils.isEmpty(mU2StatusText.getText())) {
            mU2StatusText.setText(getString(R.string.botdrop_u2_service_status_checking));
        }
        executeShizukuShellCommand(buildCheckU2StatusCommand(), result -> {
            if (!isUiAvailable() || mU2StatusText == null) {
                return;
            }

            ServiceStateResult stateResult;
            if (result != null && result.success) {
                String output = result.stdout == null ? "" : result.stdout.trim();
                if ("running".equalsIgnoreCase(output)) {
                    stateResult = new ServiceStateResult(true, null);
                } else if ("stopped".equalsIgnoreCase(output)) {
                    stateResult = new ServiceStateResult(false, null);
                } else {
                    stateResult = queryHttpEndpoint(U2_PING_URL, null);
                }
            } else {
                ServiceStateResult httpFallback = queryHttpEndpoint(U2_PING_URL, null);
                if (httpFallback.running) {
                    stateResult = httpFallback;
                } else {
                    String reason = null;
                    if (result != null) {
                        reason = !TextUtils.isEmpty(result.stderr) ? result.stderr
                            : (!TextUtils.isEmpty(result.stdout) ? result.stdout : ("exit " + result.exitCode));
                    }
                    stateResult = new ServiceStateResult(false, reason);
                }
            }

            if (stateResult.running) {
                mU2StatusText.setText(getString(R.string.botdrop_u2_service_status_running));
                setU2ServiceButtonsState(false, true);
            } else if (mU2SetupInProgress) {
                // Setup in progress – don't override status text or re-enable buttons
            } else if (TextUtils.isEmpty(stateResult.message)) {
                mU2StatusText.setText(getString(R.string.botdrop_u2_service_status_stopped));
                setU2ServiceButtonsState(true, false);
            } else {
                mU2StatusText.setText(
                    getString(R.string.botdrop_u2_service_status_stopped_with_reason, stateResult.message)
                );
                setU2ServiceButtonsState(true, false);
            }
        });
    }

    private void startU2Service() {
        if (mU2SetupInProgress) {
            return;
        }
        if (mBotDropService == null || !mBound) {
            AnalyticsManager.logEvent(this, "automation_u2_start_failed", "reason", "service_not_connected");
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        mU2SetupInProgress = true;
        setU2ServiceButtonsState(false, false);
        mU2StatusText.setText(getString(R.string.botdrop_u2_service_status_checking));
        Toast.makeText(this, getString(R.string.botdrop_u2_service_starting), Toast.LENGTH_SHORT).show();
        showU2StartProgressDialog();
        setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_prepare_env);

        new Thread(() -> {
            // Pre-step: copy u2.jar from assets to /data/local/tmp/
            String copyMessage = ensureU2JarFromAssets();
            if (!TextUtils.isEmpty(copyMessage)) {
                mU2SetupInProgress = false;
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.botdrop_u2_service_start_failed) + ": " + copyMessage,
                            Toast.LENGTH_LONG).show();
                    Logger.logWarn(LOG_TAG, "Prepare u2 jar failed: " + copyMessage);
                    showU2StartProgressFailure(
                        "copy_u2_failed",
                        getString(R.string.botdrop_u2_service_start_failed) + ": " + copyMessage
                    );
                    checkU2ServiceStatus();
                });
                return;
            }

            // Pre-step: ensure sharp is installed (also cleans broken dpkg-perl dependency)
            mBotDropService.runEnsureSharpInstalled(sharpResult -> {
                if (!isUiAvailable()) return;

                // Check if u2automator is already installed
                setU2StatusText(getString(R.string.botdrop_u2_service_status_checking));
                mBotDropService.executeU2SetupCommand(buildCheckU2AutomatorInstalledCommand(), checkResult -> {
                    if (!isUiAvailable()) return;
                    if (checkResult != null && checkResult.success) {
                        // Already installed – skip to step 4, still install IME
                        Logger.logInfo(LOG_TAG, "u2automator already installed, skipping steps 1-3");
                        // Mark skipped steps as done, then continue with IME installation
                        setU2StartProgressStep(2);
                        setU2StartProgressStep(3);
                        installAdbKeyboardIme(() -> startU2Jar());
                    } else {
                        // Not installed – run full setup steps 1-3 then start
                        runU2SetupSteps();
                    }
                });
            });
        }).start();
    }

    private void startU2Jar() {
        setU2StatusText(getString(R.string.botdrop_u2_service_prepare_step_start_u2));
        setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_start_u2);
        setU2StartProgressStep(4);
        executeShizukuShellCommand(buildStartU2Command(), step4 -> {
            if (!isUiAvailable()) return;
            if (step4 == null || !step4.success) {
                handleU2StartStepFailure("start u2.jar", step4);
                return;
            }
            completeU2StartProgress();
            mU2SetupInProgress = false;
            runOnUiThread(() -> {
                Toast.makeText(this,
                        getString(R.string.botdrop_u2_service_start_command_sent),
                        Toast.LENGTH_SHORT).show();
                mHandler.postDelayed(this::checkU2ServiceStatus, SERVICE_REFRESH_DELAY_MS);
            });
        });
    }

    private void runU2SetupSteps() {
        // Step 1/5: Trim apt sources (via Termux shell)
        setU2StatusText(getString(R.string.botdrop_u2_service_prepare_step_clean_sources));
        setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_clean_sources);
        setU2StartProgressStep(0);
        mBotDropService.executeU2SetupCommand(buildTrimAptSourcesForU2Command(), step1 -> {
            if (!isUiAvailable()) return;
            if (step1 == null || !step1.success) {
                handleU2StartStepFailure("trim apt sources", step1);
                return;
            }

            // Step 2/5: Reinstall dependencies (via Termux shell, up to 3 min)
            setU2StatusText(getString(R.string.botdrop_u2_service_prepare_step_prepare_env));
            setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_prepare_env);
            setU2StartProgressStep(1);
            mBotDropService.executeU2SetupCommand(buildReinstallDependenciesCommand(), 180, step2 -> {
                if (!isUiAvailable()) return;
                if (step2 == null || !step2.success) {
                    handleU2StartStepFailure("reinstall dependencies", step2);
                    return;
                }

                // Step 3/5: Install u2automator apt package (via Termux shell, up to 10 min)
                setU2StatusText(getString(R.string.botdrop_u2_service_prepare_step_verify));
                setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_verify);
                setU2StartProgressStep(2);
                mBotDropService.executeU2SetupCommand(buildInstallU2AutomatorCommand(), 600, step3 -> {
                    if (!isUiAvailable()) return;
                    if (step3 == null || !step3.success) {
                        handleU2StartStepFailure("install u2automator", step3);
                        return;
                    }

                    // Step 4/5: Install AdbKeyboard IME (via Shizuku shell)
                    // Step 5/5: Start u2.jar (via Shizuku)
                    installAdbKeyboardIme(() -> startU2Jar());
                });
            });
        });
    }

    private void handleU2StartStepFailure(String stepName,
            @Nullable BotDropService.CommandResult result) {
        String detail = resolveCommandFailure(result);
        String reason = mapU2StartFailureReason(stepName);
        mU2SetupInProgress = false;
        runOnUiThread(() -> {
            if (!isUiAvailable()) return;
            String message = getString(R.string.botdrop_u2_service_start_failed) + ": " + detail;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            setU2StatusText(message);
            showU2StartProgressFailure(reason, message);
            Logger.logWarn(LOG_TAG, "u2 start failed at [" + stepName + "]: " + detail);
            checkU2ServiceStatus();
        });
    }

    private void installAdbKeyboardIme(Runnable onComplete) {
        setU2StatusText(getString(R.string.botdrop_u2_service_prepare_step_install_ime));
        setU2StartProgressMessage(R.string.botdrop_u2_service_prepare_step_install_ime);
        setU2StartProgressStep(3);

        // Check if already installed via Shizuku shell
        executeShizukuShellCommand("pm list packages " + U2_IME_PACKAGE, checkResult -> {
            if (!isUiAvailable()) return;
            boolean alreadyInstalled = checkResult != null && checkResult.success
                && checkResult.stdout != null && checkResult.stdout.contains(U2_IME_PACKAGE);

            if (alreadyInstalled) {
                Logger.logInfo(LOG_TAG, "AdbKeyboard IME already installed, enabling");
                executeShizukuShellCommand("ime enable " + U2_IME_COMPONENT, enableResult -> {
                    if (!isUiAvailable()) return;
                    if (enableResult == null || !enableResult.success) {
                        handleU2StartStepFailure("enable AdbKeyboard IME", enableResult);
                        return;
                    }
                    onComplete.run();
                });
                return;
            }

            // Download APK and install
            new Thread(() -> {
                String error = downloadToExternalCache(U2_IME_APK_URL, "app-uiautomator.apk");
                if (error != null) {
                    mU2SetupInProgress = false;
                    runOnUiThread(() -> {
                        if (!isUiAvailable()) return;
                        String message = getString(R.string.botdrop_u2_service_start_failed) + ": " + error;
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        setU2StatusText(message);
                        showU2StartProgressFailure("install_ime_failed", message);
                        Logger.logWarn(LOG_TAG, "Download AdbKeyboard APK failed: " + error);
                        checkU2ServiceStatus();
                    });
                    return;
                }

                // Copy to /data/local/tmp/ first (pm install can't read app external cache)
                File apk = new File(getExternalCacheDir(), "app-uiautomator.apk");
                String tmpApk = "/data/local/tmp/app-uiautomator.apk";
                String installCmd = "cp \"" + apk.getAbsolutePath() + "\" \"" + tmpApk + "\""
                    + " && pm install -r -t \"" + tmpApk + "\""
                    + " && ime enable " + U2_IME_COMPONENT
                    + " ; rm -f \"" + tmpApk + "\"";
                executeShizukuShellCommand(installCmd, installResult -> {
                    if (!isUiAvailable()) return;
                    if (installResult == null || !installResult.success) {
                        handleU2StartStepFailure("install AdbKeyboard IME", installResult);
                        return;
                    }
                    Logger.logInfo(LOG_TAG, "AdbKeyboard IME installed and enabled");
                    onComplete.run();
                });
            }).start();
        });
    }

    /**
     * Downloads a file from URL to external cache directory.
     * @return null on success, error message on failure.
     */
    @Nullable
    private String downloadToExternalCache(String urlStr, String filename) {
        try {
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) {
                return "external cache dir unavailable";
            }
            File outFile = new File(cacheDir, filename);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                return "HTTP " + code;
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            conn.disconnect();
            Logger.logInfo(LOG_TAG, "Downloaded " + filename + " to " + outFile.getAbsolutePath());
            return null;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Download failed: " + e.getMessage());
            return e.getMessage();
        }
    }

    private void setU2StatusText(@Nullable String text) {
        if (mU2StatusText == null) {
            return;
        }
        runOnUiThread(() -> mU2StatusText.setText(text));
    }

    private void showU2StartProgressDialog() {
        if (!isUiAvailable()) {
            return;
        }

        mU2StartProgressDialog = StepProgressDialog.create(
            this,
            R.string.botdrop_u2_service_starting,
            Arrays.asList(
                getString(R.string.botdrop_u2_service_prepare_step_clean_sources),
                getString(R.string.botdrop_u2_service_prepare_step_prepare_env),
                getString(R.string.botdrop_u2_service_prepare_step_verify),
                getString(R.string.botdrop_u2_service_prepare_step_install_ime),
                getString(R.string.botdrop_u2_service_prepare_step_start_u2)
            ),
            getString(R.string.botdrop_u2_service_status_checking)
        );
        mU2StartProgressDialog.show();
        AnalyticsManager.logEvent(this, "automation_u2_start_started");
    }

    private void setU2StartProgressMessage(@StringRes int messageRes) {
        if (!isUiAvailable() || mU2StartProgressDialog == null) {
            return;
        }
        mU2StartProgressDialog.setStatus(messageRes);
    }

    private void setU2StartProgressMessage(@Nullable String message) {
        if (!isUiAvailable() || mU2StartProgressDialog == null) {
            return;
        }
        mU2StartProgressDialog.setStatus(message);
    }

    private void setU2StartProgressStep(int nextStep) {
        if (!isUiAvailable() || mU2StartProgressDialog == null) {
            return;
        }
        mU2StartProgressDialog.setStep(nextStep);
    }

    private void completeU2StartProgress() {
        if (!isUiAvailable() || mU2StartProgressDialog == null) {
            return;
        }

        AnalyticsManager.logEvent(this, "automation_u2_start_completed");
        mU2StartProgressDialog.complete(getString(R.string.botdrop_u2_service_start_command_sent));
        mHandler.postDelayed(() -> {
            if (mU2StartProgressDialog != null && mU2StartProgressDialog.isShowing()) {
                mU2StartProgressDialog.dismiss();
                mU2StartProgressDialog = null;
            }
        }, 1200);
    }

    private void showU2StartProgressFailure(String message) {
        showU2StartProgressFailure("unknown", message);
    }

    private void showU2StartProgressFailure(String reason, String message) {
        AnalyticsManager.logEvent(this, "automation_u2_start_failed", "reason", reason);
        if (mU2StartProgressDialog != null && mU2StartProgressDialog.isShowing()) {
            mU2StartProgressDialog.showError(message, () -> {
                mU2StartProgressDialog = null;
                mU2SetupInProgress = false;
            });
        }
    }

    private String mapU2StartFailureReason(String stepName) {
        if ("trim apt sources".equals(stepName) || "reinstall dependencies".equals(stepName)) {
            return "prepare_env_failed";
        }
        if ("install u2automator".equals(stepName)) {
            return "install_u2automator_failed";
        }
        if ("install AdbKeyboard IME".equals(stepName)) {
            return "install_ime_failed";
        }
        if ("enable AdbKeyboard IME".equals(stepName)) {
            return "enable_ime_failed";
        }
        if ("start u2.jar".equals(stepName)) {
            return "start_process_failed";
        }
        return "unknown";
    }

    private String buildTrimAptSourcesForU2Command() {
        return "export PREFIX=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\"\n"
            + "APT_SOURCES_LIST=\"$PREFIX/etc/apt/sources.list\"\n"
            + "APT_SOURCES_LIST_D=\"$PREFIX/etc/apt/sources.list.d\"\n"
            + "if [ -f \"$APT_SOURCES_LIST\" ]; then\n"
            + "  sed -i '/zhixianio/!{/lay2dev.gitlab.io/!d;}' \"$APT_SOURCES_LIST\"\n"
            + "fi\n"
            + "if [ -d \"$APT_SOURCES_LIST_D\" ]; then\n"
            + "  for f in \"$APT_SOURCES_LIST_D\"/*.list; do\n"
            + "    [ -f \"$f\" ] || continue\n"
            + "    sed -i '/zhixianio/!{/lay2dev.gitlab.io/!d;}' \"$f\"\n"
            + "  done\n"
            + "fi\n"
            + "if ! grep -R -E -n \"zhixianio|lay2dev.gitlab.io\" \"$APT_SOURCES_LIST\" \"$APT_SOURCES_LIST_D\" 2>/dev/null | grep -q .; then\n"
            + "  echo \"No BotDrop apt source found\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "echo \"apt sources prepared\"\n";
    }

    private String buildReinstallDependenciesCommand() {
        return "export PREFIX=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\"\n"
            + "export PATH=\"$PREFIX/bin:$PATH\"\n"
            + "if ! command -v apt >/dev/null 2>&1; then\n"
            + "  echo \"apt command unavailable\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "apt update\n"
            + "apt install -y --reinstall python python-pillow python-lxml libxml2 libxslt libicu zlib libjpeg-turbo\n"
            + "echo \"dependencies reinstalled\"\n";
    }

    private String buildCheckU2AutomatorInstalledCommand() {
        return "export PREFIX=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\"\n"
            + "export PATH=\"$PREFIX/bin:$PATH\"\n"
            + "if ! command -v dpkg >/dev/null 2>&1; then\n"
            + "  echo \"dpkg command unavailable\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "dpkg -s " + U2_AUTOMATOR_APT_PACKAGE + " >/dev/null 2>&1 && echo \"installed\" && exit 0\n"
            + "echo \"not installed\" >&2\n"
            + "exit 1\n";
    }

    private String buildInstallU2AutomatorCommand() {
        return "export PREFIX=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\"\n"
            + "export PATH=\"$PREFIX/bin:$PATH\"\n"
            + "if ! command -v apt >/dev/null 2>&1; then\n"
            + "  echo \"apt command unavailable\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "apt update\n"
            + "apt install -y " + U2_AUTOMATOR_APT_PACKAGE + "\n"
            + "echo \"u2automator installed\"\n";
    }

    private void stopU2Service() {
        if (mBotDropService == null || !mBound) {
            AnalyticsManager.logEvent(this, "automation_u2_stop_failed");
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        AnalyticsManager.logEvent(this, "automation_u2_stop_started");
        setU2ServiceButtonsState(false, false);
        mU2StatusText.setText(getString(R.string.botdrop_u2_service_status_checking));
        Toast.makeText(this, getString(R.string.botdrop_u2_service_stopping), Toast.LENGTH_SHORT).show();

        executeShizukuShellCommand(buildStopU2Command(), result -> {
            if (!isUiAvailable()) {
                return;
            }

            runOnUiThread(() -> {
                if (!isUiAvailable() || mBotDropService == null) {
                    return;
                }

                if (result == null) {
                    AnalyticsManager.logEvent(this, "automation_u2_stop_failed");
                    Toast.makeText(
                            this,
                            getString(R.string.botdrop_u2_service_stop_failed),
                            Toast.LENGTH_SHORT
                    ).show();
                    Logger.logWarn(LOG_TAG, "Stop u2 command returned null result");
                    checkU2ServiceStatus();
                    return;
                }

                if (result.success) {
                    AnalyticsManager.logEvent(this, "automation_u2_stop_completed");
                    Toast.makeText(
                            this,
                            getString(R.string.botdrop_u2_service_stop_command_sent),
                            Toast.LENGTH_SHORT
                    ).show();
                    mHandler.postDelayed(this::checkU2ServiceStatus, SERVICE_REFRESH_DELAY_MS);
                } else {
                    String shownReason = TextUtils.isEmpty(result.stderr) ? getString(R.string.botdrop_unknown) : result.stderr;
                    Toast.makeText(
                            this,
                            getString(R.string.botdrop_u2_service_stop_failed) + ": " + shownReason,
                            Toast.LENGTH_LONG
                    ).show();
                    Logger.logWarn(LOG_TAG, "Stop u2 service failed: " + shownReason);
                    AnalyticsManager.logEvent(this, "automation_u2_stop_failed");
                    checkU2ServiceStatus();
                }
            });
        });
    }

    private void setU2ServiceButtonsState(boolean startEnabled, boolean stopEnabled) {
        if (!isUiAvailable()) {
            return;
        }

        if (mStartU2ServiceButton != null) {
            mStartU2ServiceButton.setEnabled(startEnabled);
            mStartU2ServiceButton.setAlpha(startEnabled ? 1f : 0.6f);
        }

        if (mStopU2ServiceButton != null) {
            mStopU2ServiceButton.setEnabled(stopEnabled);
            mStopU2ServiceButton.setAlpha(stopEnabled ? 1f : 0.6f);
        }
    }

    private ServiceStateResult queryHttpEndpoint(String url, @Nullable String authorizationHeader) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) SERVICE_STATUS_TIMEOUT_MS);
            connection.setReadTimeout((int) SERVICE_STATUS_TIMEOUT_MS);

            if (!TextUtils.isEmpty(authorizationHeader)) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                return new ServiceStateResult(true, null);
            }
            return new ServiceStateResult(false, String.valueOf(code));
        } catch (Throwable e) {
            return new ServiceStateResult(false, e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private String readShizukuBridgeToken() {
        File tokenFile = new File(SHIZUKU_BRIDGE_CONFIG_PATH);
        if (!tokenFile.exists()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(tokenFile)))) {
            StringBuilder jsonContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }

            if (TextUtils.isEmpty(jsonContent.toString())) {
                return null;
            }

            JSONObject config = new JSONObject(jsonContent.toString());
            String token = config.optString("token", "");
            return TextUtils.isEmpty(token) ? null : token;
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to read shizuku bridge token: " + e.getMessage());
            return null;
        }
    }

    private String buildStartU2Command() {
        return "TARGET_JAR=\"" + U2_RUNTIME_JAR_PATH + "\"\n"
            + "if [ ! -f \"$TARGET_JAR\" ]; then\n"
            + "  echo \"u2.jar not found\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "if [ -f \"" + U2_PID_FILE + "\" ]; then\n"
            + "  PID=$(cat " + U2_PID_FILE + " 2>/dev/null || true)\n"
            + "  if [ -n \"$PID\" ] && kill -0 \"$PID\" 2>/dev/null; then\n"
            + "    echo \"u2 already running\"\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  rm -f " + U2_PID_FILE + "\n"
            + "fi\n"
            + "U2_RUNNING_PID=\"\"\n"
            + "for CANDIDATE in $(pidof app_process 2>/dev/null || true); do\n"
            + "  CMDLINE=$(tr '\\0' ' ' < /proc/$CANDIDATE/cmdline 2>/dev/null || true)\n"
            + "  case \"$CMDLINE\" in\n"
            + "    *\"/ com.wetest.uia2.Main\"*)\n"
            + "      U2_RUNNING_PID=\"$CANDIDATE\"\n"
            + "      break\n"
            + "      ;;\n"
            + "  esac\n"
            + "done\n"
            + "if [ -n \"$U2_RUNNING_PID\" ]; then\n"
            + "  echo \"$U2_RUNNING_PID\" > " + U2_PID_FILE + "\n"
            + "  echo \"u2 already running\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "CLASSPATH=\"$TARGET_JAR\" app_process / com.wetest.uia2.Main >/dev/null 2>&1 </dev/null &\n"
            + "U2_PID=$!\n"
            + "sleep 1\n"
            + "if [ -n \"$U2_PID\" ] && [ \"$U2_PID\" -gt 0 ]; then\n"
            + "  echo $U2_PID > " + U2_PID_FILE + "\n"
            + "fi\n"
            + "if [ -z \"$U2_PID\" ] || ! kill -0 \"$U2_PID\" 2>/dev/null; then\n"
            + "  echo \"Failed to start u2 service\" >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "echo \"u2 started\"\n";
    }

    private String buildCheckU2StatusCommand() {
        return "RUNNING=0\n"
            + "for CANDIDATE in $(pidof app_process 2>/dev/null || true); do\n"
            + "  CMDLINE=$(tr '\\0' ' ' < /proc/$CANDIDATE/cmdline 2>/dev/null || true)\n"
            + "  case \"$CMDLINE\" in\n"
            + "    *\"/ com.wetest.uia2.Main\"*)\n"
            + "      RUNNING=1\n"
            + "      break\n"
            + "      ;;\n"
            + "  esac\n"
            + "done\n"
            + "if ss -ltn 2>/dev/null | grep -q ':9008'; then\n"
            + "  RUNNING=1\n"
            + "fi\n"
            + "if [ \"$RUNNING\" -eq 1 ]; then\n"
            + "  echo \"running\"\n"
            + "else\n"
            + "  echo \"stopped\"\n"
            + "fi\n";
    }

    /**
     * Copy u2.jar from assets to app cache dir, then use Shizuku shell to copy
     * it to /data/local/tmp/ (app uid cannot write there directly).
     * Returns null on success, or error message on failure.
     */
    private String ensureU2JarFromAssets() {
        // Step 1: Copy from assets to external cache (readable by both shell and root)
        File extCacheDir = getExternalCacheDir();
        if (extCacheDir == null) {
            return "External cache directory unavailable";
        }
        File cacheJar = new File(extCacheDir, "u2.jar");
        try (InputStream input = getAssets().open(U2_ASSET_PATH);
             OutputStream output = new FileOutputStream(cacheJar)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to copy u2.jar to external cache: " + e.getMessage());
            return e.getMessage();
        }

        // Step 2: Use Shizuku shell to copy from cache to /data/local/tmp/
        String cpCommand = "cp '" + cacheJar.getAbsolutePath() + "' '" + U2_RUNTIME_JAR_PATH + "' && chmod 644 '" + U2_RUNTIME_JAR_PATH + "'";
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] errorHolder = {null};

        executeShizukuShellCommand(cpCommand, result -> {
            if (result == null || !result.success) {
                errorHolder[0] = result == null ? "Shizuku unavailable" : resolveCommandFailure(result);
            }
            latch.countDown();
        });

        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                return "Timeout copying u2.jar via Shizuku";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted copying u2.jar";
        }

        return errorHolder[0];
    }

    private String buildStopU2Command() {
        return "if [ -f \"" + U2_PID_FILE + "\" ]; then\n"
            + "  PID=$(cat " + U2_PID_FILE + " 2>/dev/null || true)\n"
            + "  if [ -n \"$PID\" ]; then\n"
            + "    kill \"$PID\" 2>/dev/null || true\n"
            + "  fi\n"
            + "  rm -f " + U2_PID_FILE + "\n"
            + "fi\n"
            + "for CANDIDATE in $(pidof app_process 2>/dev/null || true); do\n"
            + "  CMDLINE=$(tr '\\0' ' ' < /proc/$CANDIDATE/cmdline 2>/dev/null || true)\n"
            + "  case \"$CMDLINE\" in\n"
            + "    *\"/ com.wetest.uia2.Main\"*)\n"
            + "      kill \"$CANDIDATE\" 2>/dev/null || true\n"
            + "      ;;\n"
            + "  esac\n"
            + "done\n"
            + "sleep 1\n"
            + "for CANDIDATE in $(pidof app_process 2>/dev/null || true); do\n"
            + "  CMDLINE=$(tr '\\0' ' ' < /proc/$CANDIDATE/cmdline 2>/dev/null || true)\n"
            + "  case \"$CMDLINE\" in\n"
            + "    *\"/ com.wetest.uia2.Main\"*)\n"
            + "      kill -9 \"$CANDIDATE\" 2>/dev/null || true\n"
            + "      ;;\n"
            + "  esac\n"
            + "done\n"
            + "echo \"u2 stop requested\"\n";
    }

    private void executeShizukuShellCommand(String command, BotDropService.CommandCallback callback) {
        executeShizukuShellCommand(command, SHIZUKU_SHELL_COMMAND_TIMEOUT_MS, callback);
    }

    private void executeShizukuShellCommand(
        String command,
        long timeoutMs,
        BotDropService.CommandCallback callback
    ) {
        new Thread(() -> {
            if (mShizukuShellExecutor == null) {
                runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onResult(new BotDropService.CommandResult(false, "", "Shizuku shell executor not initialized", -1));
                    }
                });
                return;
            }

            if (!mShizukuShellExecutor.isBound()) {
                if (!mShizukuShellExecutor.waitForConnection(SHIZUKU_SHELL_CONNECT_TIMEOUT_MS)) {
                    runOnUiThread(() -> {
                        if (callback != null) {
                            callback.onResult(new BotDropService.CommandResult(false, "", "Shizuku shell unavailable", -1));
                        }
                    });
                    return;
                }
            }

            int timeout = timeoutMs <= 0 || timeoutMs > Integer.MAX_VALUE
                    ? (int) SHIZUKU_SHELL_COMMAND_TIMEOUT_MS
                    : (int) timeoutMs;

            mShizukuShellExecutor.execute(command, timeout, result -> {
                if (callback == null) {
                    return;
                }
                runOnUiThread(() -> callback.onResult(
                    new BotDropService.CommandResult(
                        result.success,
                        result.stdout == null ? "" : result.stdout,
                        result.stderr == null ? "" : result.stderr,
                        result.exitCode
                    )
                ));
            });
        }).start();
    }

    private String resolveCommandFailure(@Nullable BotDropService.CommandResult result) {
        if (result == null) {
            return getString(R.string.botdrop_unknown);
        }

        if (!TextUtils.isEmpty(result.stderr)) {
            return result.stderr;
        }
        if (!TextUtils.isEmpty(result.stdout)) {
            return result.stdout;
        }
        return "exit " + result.exitCode;
    }

    private void runShizukuPermissionButtonStateBusy(boolean busy, @Nullable String busyText) {
        if (mShizukuPermissionButton == null) {
            return;
        }
        mShizukuPermissionButton.setEnabled(!busy);
        mShizukuPermissionButton.setAlpha(busy ? 0.6f : 1f);
        if (busyText != null) {
            mShizukuPermissionButton.setText(busyText);
        } else {
            mShizukuPermissionButton.setText(getString(R.string.action_request_shizuku_permission));
        }
    }

    private boolean isUiAvailable() {
        return !isFinishing();
    }

    private boolean startOfficialShizukuHome() {
        if (startShizukuActivity(new Intent(this, MainActivity.class))) {
            return true;
        }

        Intent launcherIntent = resolveShizukuManagerLauncherActivity();
        if (launcherIntent != null && startShizukuActivity(launcherIntent)) {
            return true;
        }

        return false;
    }

    private Intent resolveShizukuManagerLauncherActivity() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launcherIntent.setPackage(getPackageName());

        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL);
        if (activities == null) {
            return null;
        }

        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null) {
                continue;
            }

            String className = activity.activityInfo.name;
            if (className != null && className.startsWith("moe.shizuku.manager")) {
                return new Intent(launcherIntent).setClassName(activity.activityInfo.packageName, className);
            }
        }
        return null;
    }

    private boolean startShizukuActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Logger.logWarn(LOG_TAG, "Internal Shizuku home not found: " + intent);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to open internal Shizuku home: " + intent + ", " + e.getMessage());
        }
        return false;
    }

    private static class ServiceStateResult {
        final boolean running;
        final String message;

        private ServiceStateResult(boolean running, @Nullable String message) {
            this.running = running;
            this.message = message;
        }
    }
}
