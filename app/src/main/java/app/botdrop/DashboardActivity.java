package app.botdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Environment;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import app.botdrop.shizuku.ShizukuBridgeService;
import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import moe.shizuku.manager.MainActivity;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Dashboard activity - main screen after setup is complete.
 * Shows gateway status, connected channels, and control buttons.
 * Auto-refreshes status every 5 seconds.
 */
public class DashboardActivity extends Activity {

    private static final String LOG_TAG = "DashboardActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_gateway";
    private static final int STATUS_REFRESH_INTERVAL_MS = 5000; // 5 seconds
    private static final int ERROR_CHECK_INTERVAL_MS = 15000; // 15 seconds
    private static final String MODEL_LIST_COMMAND = OpenclawModelListUtils.buildPreferredModelListCommand(true);
    private static final String MODEL_PREFS_NAME = "openclaw_model_cache_v1";
    private static final String MODEL_CACHE_KEY_PREFIX = "models_by_version_";
    private static final int GATEWAY_LOG_TAIL_LINES = 300;
    private static final long OPENCLAW_LOG_TAIL_POLL_INTERVAL_MS = 2500L;
    private static final int GATEWAY_DEBUG_LOG_TAIL_LINES = 120;
    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT = 8;
    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS = 700;
    // Version management constants moved to OpenclawVersionUtils
    private static final String OPENCLAW_DASHBOARD_COMMAND = "openclaw dashboard --no-open 2>&1";
    private static final String OPENCLAW_GATEWAY_PRECHECK_COMMAND = "openclaw --version";
    private static final int OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS = 180;
    private static final int OPENCLAW_DEFAULT_WEB_UI_PORT = 18789;
    private static final String OPENCLAW_DEFAULT_WEB_UI_PATH = "/";
    private static final String OPENCLAW_DEFAULT_WEB_UI_URL = "http://127.0.0.1:" + OPENCLAW_DEFAULT_WEB_UI_PORT + OPENCLAW_DEFAULT_WEB_UI_PATH;
    private static final String OPENCLAW_WEB_UI_TOKEN_KEY = "token";
    private static final String OPENCLAW_HOME_FOLDER = ".openclaw";
    private static final String BOTDROP_HOME_FOLDER = "botdrop";
    private static final String GATEWAY_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.log";
    private static final String GATEWAY_DEBUG_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway-debug.log";
    private static final String GATEWAY_LOG_LABEL = "gateway.log";
    private static final String GATEWAY_DEBUG_LOG_LABEL = "gateway-debug.log";
    private static final String OPENCLAW_BACKUP_DIRECTORY = "BotDrop/openclaw";
    private static final String OPENCLAW_BACKUP_FILE_PREFIX = "openclaw-config-backup-";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION = ".zip";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION_JSON = ".json";
    private static final String OPENCLAW_CONFIG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json";
    private static final String OPENCLAW_AUTH_PROFILES_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";
    private static final String OPENCLAW_BACKUP_DATE_PATTERN = "yyyyMMdd_HHmmss";
    private static final String OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY = "openclawConfig";
    private static final String OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY = "authProfiles";
    private static final String OPENCLAW_BACKUP_META_CREATED_AT_KEY = "createdAt";
    private static final int OPENCLAW_BACKUP_IO_BUFFER_SIZE = 8192;
    private static final int OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE = 3001;
    private static final String OPENCLAW_RESTORE_STAGING_DIR_PREFIX = ".openclaw_restore_staging_";
    private static final String OPENCLAW_RESTORE_BACKUP_DIR_PREFIX = ".openclaw_restore_backup_";
    private static final String BOTDROP_RESTORE_BACKUP_DIR_PREFIX = ".botdrop_restore_backup_";
    private static final Pattern WEB_UI_URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s\"'`<>\\)\\]}]+");
    private static final Pattern HOST_PORT_PATTERN =
            Pattern.compile("(?i)\\b(127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[[0-9a-f:]+\\]|[a-z0-9._-]+):(\\d{2,5})\\b");
    private static final Pattern GATEWAY_TOKEN_QUERY_PATTERN =
            Pattern.compile("(?i)token=([^\\s\"'`<>\\)\\]}&]+)");
    private static String getOpenclawLogTailCommand(String logFile, int tailLines) {
        return "if [ -f " + logFile + " ]; then\n" +
                "  tail -n " + tailLines + " " + logFile + "\n" +
                "else\n" +
                "  echo 'No log file at " + logFile + "'\n" +
                "fi\n";
    }

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
    private TextView mFeishuStatus;
    private TextView mQQBotStatus;
    private View mTelegramChannelRow;
    private View mDiscordChannelRow;
    private View mFeishuChannelRow;
    private View mQQBotChannelRow;
    private View mStartButton;
    private View mStopButton;
    private View mRestartButton;
    private ImageView mStartButtonIcon;
    private ImageView mStopButtonIcon;
    private ImageView mRestartButtonIcon;
    private TextView mStartButtonLabel;
    private TextView mStopButtonLabel;
    private TextView mRestartButtonLabel;
    private View mSshCard;
    private TextView mSshInfoText;
    private View mUpdateBanner;
    private TextView mUpdateBannerText;
    private TextView mCurrentModelText;
    private View mGatewayErrorBanner;
    private TextView mGatewayErrorText;
    private TextView mOpenclawVersionText;
    private TextView mOpenclawCheckUpdateButton;
    private TextView mOpenclawLogButton;
    private TextView mOpenclawWebUiButton;
    private TextView mOpenclawBackupButton;
    private TextView mOpenclawRestoreButton;
    private Button mOpenAutomationPanelButton;
    private ImageButton mBackToAgentSelectionButton;
    private String mOpenclawLatestUpdateVersion;
    private AlertDialog mOpenclawUpdateDialog;
    private AlertDialog mOpenclawVersionManagerDialog;
    private boolean mOpenclawManualCheckRequested;
    private boolean mUiVisible = true;
    private boolean mOpenclawWebUiOpening;
    private boolean mOpenclawVersionActionInProgress;

    private BotDropService mBotDropService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;
    private long mLastErrorCheckAtMs = 0L;
    private String mLastErrorMessage;
    private Runnable mPendingOpenclawStorageAction;
    private Runnable mPendingOpenclawStorageDeniedAction;
    private OnBackInvokedCallback mOnBackInvokedCallback;
    private interface ModelListPrefetchCallback {
        void onFinished(boolean success);
    }

    private interface OpenclawWebUiUrlCallback {
        void onUrlResolved(String url);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mBotDropService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");

            // Start status refresh
            startStatusRefresh();

            // Start gateway monitor service
            startGatewayMonitorService();

            // Keep embedded Shizuku bridge warm for openclaw/command fallback path
            startShizukuBridgeService();

            // Load current model
            loadCurrentModel();

            // Check for OpenClaw updates
            checkOpenclawUpdate();

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mBotDropService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_dashboard);

        // Create notification channel
        createNotificationChannel();

        // Initialize views
        mStatusText = findViewById(R.id.status_text);
        mUptimeText = findViewById(R.id.uptime_text);
        mStatusIndicator = findViewById(R.id.status_indicator);
        mTelegramStatus = findViewById(R.id.telegram_status);
        mDiscordStatus = findViewById(R.id.discord_status);
        mFeishuStatus = findViewById(R.id.feishu_status);
        mQQBotStatus = findViewById(R.id.qqbot_status);
        mTelegramChannelRow = findViewById(R.id.telegram_channel_row);
        mDiscordChannelRow = findViewById(R.id.discord_channel_row);
        mFeishuChannelRow = findViewById(R.id.feishu_channel_row);
        mQQBotChannelRow = findViewById(R.id.qqbot_channel_row);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mRestartButton = findViewById(R.id.btn_restart);
        mStartButtonIcon = findViewById(R.id.btn_start_icon);
        mStopButtonIcon = findViewById(R.id.btn_stop_icon);
        mRestartButtonIcon = findViewById(R.id.btn_restart_icon);
        mStartButtonLabel = findViewById(R.id.btn_start_label);
        mStopButtonLabel = findViewById(R.id.btn_stop_label);
        mRestartButtonLabel = findViewById(R.id.btn_restart_label);
        Button openTerminalButton = findViewById(R.id.btn_open_terminal);
        ImageButton openSettingsButton = findViewById(R.id.btn_open_botdrop_settings);
        mCurrentModelText = findViewById(R.id.current_model_text);
        Button changeModelButton = findViewById(R.id.btn_change_model);
        mGatewayErrorBanner = findViewById(R.id.gateway_error_banner);
        mGatewayErrorText = findViewById(R.id.gateway_error_text);
        mOpenAutomationPanelButton = findViewById(R.id.btn_open_automation_panel);

        // Setup button listeners
        mStartButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "dashboard_start_tap");
            startGateway();
        });
        mStopButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "dashboard_stop_tap");
            stopGateway();
        });
        mRestartButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "dashboard_restart_tap");
            restartGatewayForControl();
        });
        setAsAccessibleButton(mStartButton);
        setAsAccessibleButton(mStopButton);
        setAsAccessibleButton(mRestartButton);
        openTerminalButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "dashboard_terminal_tap");
            openTerminal();
        });
        changeModelButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "dashboard_model_tap");
            showModelSelector();
        });
        if (mOpenAutomationPanelButton != null) {
            mOpenAutomationPanelButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "dashboard_automation_tap");
                openAutomationPanel();
            });
        }
        if (openSettingsButton != null) {
            openSettingsButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "dashboard_settings_tap");
                openBotdropSettings();
            });
        }

        mSshCard = findViewById(R.id.ssh_card);
        mSshInfoText = findViewById(R.id.ssh_info_text);

        // Update banner
        mUpdateBanner = findViewById(R.id.update_banner);
        mUpdateBannerText = findViewById(R.id.update_banner_text);

        // OpenClaw version + check button
        mOpenclawVersionText = findViewById(R.id.openclaw_version_text);
        mOpenclawCheckUpdateButton = findViewById(R.id.btn_check_openclaw_update);
        if (mOpenclawCheckUpdateButton != null) {
            mOpenclawCheckUpdateButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "openclaw_update_check_tap");
                forceCheckOpenclawUpdate();
            });
        }
        mOpenclawLogButton = findViewById(R.id.btn_view_openclaw_log);
        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "openclaw_log_tap");
                showOpenclawLog();
            });
        }
        mBackToAgentSelectionButton = findViewById(R.id.btn_back_to_agent_selection);
        if (mBackToAgentSelectionButton != null) {
            mBackToAgentSelectionButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "dashboard_agent_select_tap");
                openAgentSelection();
            });
        }
        mOpenclawWebUiButton = findViewById(R.id.btn_open_openclaw_web_ui);
        if (mOpenclawWebUiButton != null) {
            mOpenclawWebUiButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "openclaw_webui_tap");
                openOpenclawWebUi();
            });
        }
        mOpenclawBackupButton = findViewById(R.id.btn_backup_openclaw_config);
        if (mOpenclawBackupButton != null) {
            mOpenclawBackupButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "openclaw_backup_tap");
                backupOpenclawConfigToSdcard();
            });
        }
        mOpenclawRestoreButton = findViewById(R.id.btn_restore_openclaw_config);
        if (mOpenclawRestoreButton != null) {
            mOpenclawRestoreButton.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "openclaw_restore_tap");
                restoreOpenclawConfigFromSdcard();
            });
        }
        if (mTelegramChannelRow != null) {
            mTelegramChannelRow.setOnClickListener(
                v -> {
                    AnalyticsManager.logEvent(this, "dashboard_channel_tap", "platform", ChannelConfigMeta.PLATFORM_TELEGRAM);
                    openChannelConfig(ChannelConfigMeta.PLATFORM_TELEGRAM);
                }
            );
        }
        if (mDiscordChannelRow != null) {
            mDiscordChannelRow.setOnClickListener(
                v -> {
                    AnalyticsManager.logEvent(this, "dashboard_channel_tap", "platform", ChannelConfigMeta.PLATFORM_DISCORD);
                    openChannelConfig(ChannelConfigMeta.PLATFORM_DISCORD);
                }
            );
        }
        if (mFeishuChannelRow != null) {
            mFeishuChannelRow.setOnClickListener(
                v -> {
                    AnalyticsManager.logEvent(this, "dashboard_channel_tap", "platform", ChannelConfigMeta.PLATFORM_FEISHU);
                    openChannelConfig(ChannelConfigMeta.PLATFORM_FEISHU);
                }
            );
        }
        if (mQQBotChannelRow != null) {
            mQQBotChannelRow.setOnClickListener(
                v -> {
                    AnalyticsManager.logEvent(this, "dashboard_channel_tap", "platform", ChannelConfigMeta.PLATFORM_QQBOT);
                    openChannelConfig(ChannelConfigMeta.PLATFORM_QQBOT);
                }
            );
        }

        // Load channel info
        loadChannelInfo();

        // Load SSH info
        loadSshInfo();

        // Bind to service
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Check for app updates (also picks up results from launcher check)
        UpdateChecker.check(this, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String downloadUrl, String notes) {
                AnalyticsManager.logEvent(DashboardActivity.this, "app_update_available");
                showUpdateBanner(latestVersion, downloadUrl);
            }

            @Override
            public void onNoUpdate() {
                AnalyticsManager.logEvent(DashboardActivity.this, "app_update_none");
                hideUpdateBanner();
            }
        });

        // Also check stored result in case launcher already fetched it
        String[] stored = UpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showUpdateBanner(stored[0], stored[1]);
        }
        registerBackInvokedCallback();

    }

    private void registerBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }

        mOnBackInvokedCallback = () -> openAgentSelection();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            mOnBackInvokedCallback
        );
    }

    private void openAgentSelection() {
        Intent intent = new Intent(this, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_AGENT_SELECT);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openBotdropSettings() {
        Intent intent = new Intent(this, BotDropSettingsActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Cancel all pending callbacks to prevent memory leak
        mHandler.removeCallbacksAndMessages(null);
        mStatusRefreshRunnable = null;

        dismissOpenclawUpdateDialog();
        if (mOpenclawVersionManagerDialog != null && mOpenclawVersionManagerDialog.isShowing()) {
            mOpenclawVersionManagerDialog.dismiss();
        }
        mOpenclawVersionManagerDialog = null;
        
        // Unbind from service
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mOnBackInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mOnBackInvokedCallback);
            mOnBackInvokedCallback = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mUiVisible = false;
        mOpenclawWebUiOpening = false;
        stopStatusRefresh();
        mHandler.removeCallbacksAndMessages(null);
        setOpenclawWebUiButtonState(false, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AnalyticsManager.logScreen(this, "dashboard_main", "DashboardActivity");
        mUiVisible = true;
        if (mBound) {
            startStatusRefresh();
            refreshStatus();
        }
        loadChannelInfo();
    }

    /**
     * Check whether it is safe to show a dialog on this Activity.
     */
    private boolean canShowDialog() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            openAgentSelection();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        openAgentSelection();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    private void stopStatusRefresh() {
        if (mStatusRefreshRunnable != null) {
            mHandler.removeCallbacks(mStatusRefreshRunnable);
            mStatusRefreshRunnable = null;
        }
    }

    private void setOpenclawWebUiButtonState(boolean opening, String statusText) {
        if (mOpenclawWebUiButton == null) {
            return;
        }

        mOpenclawWebUiButton.setEnabled(!opening);
        mOpenclawWebUiButton.setAlpha(opening ? 0.6f : 1f);

        if (TextUtils.isEmpty(statusText)) {
            mOpenclawWebUiButton.setText(getString(R.string.botdrop_open_web_ui));
        } else {
            mOpenclawWebUiButton.setText(statusText);
        }
    }

    private void backupOpenclawConfigToSdcard() {
        runWithOpenclawStoragePermission(() -> {
            setButtonEnabled(mOpenclawBackupButton, false);
            new Thread(() -> {
                String backupPath = createOpenclawBackupFile();
                runOnUiThread(() -> {
                    setButtonEnabled(mOpenclawBackupButton, true);
                    if (TextUtils.isEmpty(backupPath)) {
                    Toast.makeText(this, getString(R.string.botdrop_no_openclaw_data_folder), Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(
                    this,
                    getString(R.string.botdrop_openclaw_backup_created, backupPath),
                    Toast.LENGTH_LONG
                ).show();
            });
        }).start();
        }, () -> Toast.makeText(
            this,
            getString(R.string.botdrop_backup_permission_denied),
            Toast.LENGTH_SHORT
        ).show());
    }

    private void restoreOpenclawConfigFromSdcard() {
        runWithOpenclawStoragePermission(() -> {
            File backupFile = getLatestOpenclawBackupFile();
            if (backupFile == null) {
                Toast.makeText(
                    this,
                    getString(R.string.botdrop_no_backup_found, getOpenclawBackupDirectory().getAbsolutePath()),
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }

            confirmOpenclawRestore(backupFile);
            }, () -> Toast.makeText(
                this,
                getString(R.string.botdrop_backup_permission_denied),
                Toast.LENGTH_SHORT
            ).show());
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action) {
        runWithOpenclawStoragePermission(action, null);
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action, @Nullable Runnable deniedAction) {
        File backupDir = getOpenclawBackupDirectory();
        if (isOpenclawStoragePermissionGranted()) {
            action.run();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (PermissionUtils.requestManageStorageExternalPermission(this, OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) == null) {
                mPendingOpenclawStorageAction = action;
                mPendingOpenclawStorageDeniedAction = deniedAction;
            } else if (deniedAction != null) {
                deniedAction.run();
            }
            return;
        }

        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermissionIfPathOnPrimaryExternalStorage(
            this,
            backupDir.getAbsolutePath(),
            OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE,
            true
        )) {
            action.run();
            return;
        }
        mPendingOpenclawStorageAction = action;
        mPendingOpenclawStorageDeniedAction = deniedAction;
    }

    private void retryPendingOpenclawStorageActionIfPermitted() {
        Runnable action = mPendingOpenclawStorageAction;
        Runnable deniedAction = mPendingOpenclawStorageDeniedAction;
        if (action == null) {
            return;
        }
        mPendingOpenclawStorageAction = null;
        mPendingOpenclawStorageDeniedAction = null;
        if (!isOpenclawStoragePermissionGranted()) {
            if (deniedAction != null) {
                deniedAction.run();
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.botdrop_backup_permission_denied),
                    Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }
        action.run();
    }

    private boolean isOpenclawStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Environment.isExternalStorageManager();
        }
        return PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this));
    }

    private void confirmOpenclawRestore(File backupFile) {
        if (!canShowDialog()) return;
        String createdAtText = formatBackupTimestamp(readBackupCreatedAt(backupFile));
        String message = getString(
            R.string.botdrop_restore_openclaw_data_message,
            backupFile.getName(),
            createdAtText
        );

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.botdrop_restore_openclaw_data))
            .setMessage(message)
            .setNegativeButton(getString(R.string.botdrop_cancel), null)
            .setPositiveButton(getString(R.string.botdrop_restore), (dialog, which) -> performOpenclawRestore(backupFile))
            .show();
    }

    private void performOpenclawRestore(File backupFile) {
        setButtonEnabled(mOpenclawRestoreButton, false);
        new Thread(() -> {
            boolean restored = applyOpenclawBackup(backupFile);
            runOnUiThread(() -> {
                setButtonEnabled(mOpenclawRestoreButton, true);
                if (!restored) {
                    Toast.makeText(this, getString(R.string.botdrop_failed_openclaw_backup_restore), Toast.LENGTH_SHORT).show();
                    return;
                }
                loadCurrentModel();
                loadChannelInfo();
                    Toast.makeText(this, getString(R.string.botdrop_openclaw_data_restored), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private boolean applyOpenclawBackup(File backupFile) {
        if (isLegacyOpenclawBackupFile(backupFile)) {
            return applyLegacyOpenclawBackup(backupFile);
        }
        File openclawDir = getOpenclawHomeDirectory();
        File botdropDir = getBotdropHomeDirectory();
        if (openclawDir == null || botdropDir == null) {
            return false;
        }

        File homeDir = openclawDir.getParentFile();
        if (homeDir != null && !homeDir.exists() && !homeDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to recreate openclaw home parent: " + homeDir.getAbsolutePath());
            return false;
        }
        if (homeDir == null) {
            Logger.logWarn(LOG_TAG, "OpenClaw home parent directory is null");
            return false;
        }

        File stagingDir = createOpenclawRestoreStagingDirectory(homeDir);
        if (stagingDir == null) {
            return false;
        }

        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to create restore staging directory: " + stagingDir.getAbsolutePath());
            return false;
        }

        File[] restoreTargets = {openclawDir, botdropDir};
        File[] rollbackDirs = new File[restoreTargets.length];
        boolean[] restoredTargetExists = new boolean[restoreTargets.length];
        boolean restoreSucceeded = false;
        try {
            if (!extractOpenclawBackupToDirectory(backupFile, stagingDir)) {
                return false;
            }

            boolean hasAnyRestoredDirectory = false;
            for (int i = 0; i < restoreTargets.length; i++) {
                File targetDir = restoreTargets[i];
                File restoredSourceDir = new File(stagingDir, targetDir.getName());
                if (!restoredSourceDir.exists()) {
                    continue;
                }

                hasAnyRestoredDirectory = true;
                restoredTargetExists[i] = true;

                if (targetDir.exists()) {
                    rollbackDirs[i] = createOpenclawRollbackDirectory(homeDir, targetDir.getName());
                    if (rollbackDirs[i] == null) {
                        Logger.logWarn(LOG_TAG, "Failed to create backup directory for restore of " + targetDir.getName());
                        return false;
                    }

                    if (!targetDir.renameTo(rollbackDirs[i])) {
                        Logger.logWarn(LOG_TAG, "Failed to backup current " + targetDir.getName() + " directory before restore");
                        return false;
                    }
                }

                if (!restoredSourceDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to move restored " + targetDir.getName() + " directory into place");
                    return false;
                }
            }

            if (!hasAnyRestoredDirectory) {
                Logger.logWarn(LOG_TAG, "No restorable data directory found in backup");
                return false;
            }

            for (int i = 0; i < rollbackDirs.length; i++) {
                if (rollbackDirs[i] != null && rollbackDirs[i].exists()) {
                    if (!deleteRecursively(rollbackDirs[i])) {
                        Logger.logWarn(LOG_TAG, "Failed to delete previous backup backup directory for " + restoreTargets[i].getName());
                    }
                }
            }

            restoreSucceeded = true;
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            for (int i = 0; i < rollbackDirs.length; i++) {
                File targetDir = restoreTargets[i];
                File rollbackDir = rollbackDirs[i];
                if (rollbackDir == null) {
                    if (restoredTargetExists[i] && targetDir.exists()) {
                        deleteRecursively(targetDir);
                    }
                    continue;
                }

                if (targetDir.exists() && !deleteRecursively(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to clean partially restored " + targetDir.getName() + " directory");
                }

                if (!rollbackDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to rollback " + targetDir.getName() + " directory after restore failure");
                }
            }
            return false;
        } finally {
            if (!deleteRecursively(stagingDir)) {
                Logger.logWarn(LOG_TAG, "Failed to delete restore staging directory: " + stagingDir.getAbsolutePath());
            }
        }
    }

    private boolean applyLegacyOpenclawBackup(@NonNull File backupFile) {
        JSONObject backupPayload = readJsonFromFile(backupFile);
        if (backupPayload == null) {
            return false;
        }

        File openclawDir = getOpenclawHomeDirectory();
        if (openclawDir == null) {
            return false;
        }

        File homeDir = openclawDir.getParentFile();
        if (homeDir != null && !homeDir.exists() && !homeDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to recreate openclaw home parent: " + homeDir.getAbsolutePath());
            return false;
        }
        if (homeDir == null) {
            Logger.logWarn(LOG_TAG, "OpenClaw home parent directory is null");
            return false;
        }

        JSONObject openclawConfig = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY);
        JSONObject authProfiles = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY);
        if (openclawConfig == null && authProfiles == null) {
            Logger.logWarn(LOG_TAG, "Legacy backup has no recoverable OpenClaw payload");
            return false;
        }

        File openclawConfigFile = new File(OPENCLAW_CONFIG_FILE);
        File authProfilesFile = new File(OPENCLAW_AUTH_PROFILES_FILE);
        File rollbackDir = null;
        File targetDir = openclawDir;

        try {
            if (targetDir.exists()) {
                rollbackDir = createOpenclawRollbackDirectory(homeDir, OPENCLAW_HOME_FOLDER);
                if (rollbackDir == null) {
                    Logger.logWarn(LOG_TAG, "Failed to create backup directory for legacy restore");
                    return false;
                }
                if (!targetDir.renameTo(rollbackDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to backup current .openclaw directory before legacy restore");
                    return false;
                }
            }

            if (openclawConfig != null && !writeJsonToFile(openclawConfigFile, openclawConfig)) {
                Logger.logWarn(LOG_TAG, "Failed to restore legacy openclaw.json");
                return false;
            }

            if (authProfiles != null && !writeJsonToFile(authProfilesFile, authProfiles)) {
                Logger.logWarn(LOG_TAG, "Failed to restore legacy auth-profiles.json");
                return false;
            }

            if (rollbackDir != null && rollbackDir.exists() && !deleteRecursively(rollbackDir)) {
                Logger.logWarn(LOG_TAG, "Failed to delete legacy restore backup directory: " + rollbackDir.getAbsolutePath());
            }

            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore legacy OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            if (rollbackDir != null && rollbackDir.exists()) {
                if (targetDir.exists() && !deleteRecursively(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to clean partially restored .openclaw directory after legacy restore failure");
                }
                if (!rollbackDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to rollback .openclaw directory after legacy restore failure");
                }
            }
            return false;
        }
    }

    private boolean isLegacyOpenclawBackupFile(@NonNull File backupFile) {
        return backupFile.getName().endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON);
    }

    private File createOpenclawRestoreStagingDirectory(@NonNull File homeDir) {
        for (int suffix = 0; suffix < 10; suffix++) {
            File stagingDir = new File(homeDir, OPENCLAW_RESTORE_STAGING_DIR_PREFIX + System.currentTimeMillis() + "_" + suffix);
            if (!stagingDir.exists()) {
                return stagingDir;
            }
        }
        return null;
    }

    private File createOpenclawRollbackDirectory(@NonNull File homeDir, @NonNull String targetName) {
        String prefix = OPENCLAW_HOME_FOLDER.equals(targetName)
            ? OPENCLAW_RESTORE_BACKUP_DIR_PREFIX
            : BOTDROP_RESTORE_BACKUP_DIR_PREFIX;
        for (int suffix = 0; suffix < 10; suffix++) {
            File rollbackDir = new File(homeDir, prefix + System.currentTimeMillis() + "_" + suffix);
            if (!rollbackDir.exists()) {
                return rollbackDir;
            }
        }
        return null;
    }

    private String createOpenclawBackupFile() {
        File homeDir = getOpenclawHomeParentDirectory();
        if (homeDir == null) {
            return null;
        }
        File openclawDir = getOpenclawHomeDirectory();
        File botdropDir = getBotdropHomeDirectory();
        if (!openclawDir.exists() && !botdropDir.exists()) {
            return null;
        }

        File backupDir = getOpenclawBackupDirectory();
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            return null;
        }

        File backupFile = new File(
            backupDir,
            OPENCLAW_BACKUP_FILE_PREFIX + formatBackupTimestamp(System.currentTimeMillis()) + OPENCLAW_BACKUP_FILE_EXTENSION
        );

        try {
            boolean archived = createOpenclawBackupZip(homeDir, backupFile, openclawDir, botdropDir);
            if (!archived) {
                return null;
            }

            return backupFile.getAbsolutePath();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create OpenClaw backup: " + e.getMessage());
            return null;
        }
    }

    private File getLatestOpenclawBackupFile() {
        File backupDir = getOpenclawBackupDirectory();
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            return null;
        }

        File[] candidates = backupDir.listFiles((dir, name) ->
            name != null
                && name.startsWith(OPENCLAW_BACKUP_FILE_PREFIX)
                && (name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION) || name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON))
        );
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        Arrays.sort(candidates, Comparator.comparingLong(File::lastModified));
        return candidates[candidates.length - 1];
    }

    private File getOpenclawBackupDirectory() {
        File documentsDir = Environment.getExternalStorageDirectory();
        return new File(documentsDir, OPENCLAW_BACKUP_DIRECTORY);
    }

    private long readBackupCreatedAt(File backupFile) {
        if (backupFile == null || !backupFile.exists()) {
            return 0L;
        }

        String name = backupFile.getName();
        if (name.startsWith(OPENCLAW_BACKUP_FILE_PREFIX)
            && (name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION) || name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON))) {
            String extension = name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON)
                ? OPENCLAW_BACKUP_FILE_EXTENSION_JSON
                : OPENCLAW_BACKUP_FILE_EXTENSION;
            String timestampPart = name.substring(
                OPENCLAW_BACKUP_FILE_PREFIX.length(),
                name.length() - extension.length()
            );
            try {
                Date parsed = new SimpleDateFormat(OPENCLAW_BACKUP_DATE_PATTERN, Locale.US).parse(timestampPart);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {
            }
        }

        return backupFile.lastModified();
    }

    private String formatBackupTimestamp(long timeMs) {
        if (timeMs <= 0L) {
            timeMs = System.currentTimeMillis();
        }
        return new SimpleDateFormat(OPENCLAW_BACKUP_DATE_PATTERN, Locale.US).format(new Date(timeMs));
    }

    private boolean createOpenclawBackupZip(
        @NonNull File sourceDir,
        @NonNull File outputFile,
        @NonNull File... sourceDataDirectories
    ) {
        byte[] buffer = new byte[OPENCLAW_BACKUP_IO_BUFFER_SIZE];
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            boolean hasEntries = false;
            for (File sourceDataDirectory : sourceDataDirectories) {
                if (sourceDataDirectory == null || !sourceDataDirectory.exists() || !sourceDataDirectory.isDirectory()) {
                    continue;
                }
                if (!addOpenclawDirectoryEntriesToZip(sourceDir, sourceDataDirectory, zos, buffer)) {
                    return false;
                }
                hasEntries = true;
            }
            return hasEntries;
        } catch (java.io.IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create backup zip", e);
            return false;
        }
    }

    private boolean addOpenclawDirectoryEntriesToZip(
        @NonNull File sourceDir,
        @NonNull File current,
        @NonNull ZipOutputStream zos,
        @NonNull byte[] buffer
    ) throws java.io.IOException {
        if (current.equals(sourceDir)) {
            return true;
        }

        String sourcePath = sourceDir.getAbsolutePath();
        String childPath = current.getAbsolutePath();
        String relativePath = childPath.equals(sourcePath)
            ? ""
            : childPath.substring(sourcePath.length() + 1).replace('\\', '/');

        if (current.isDirectory()) {
            if (!relativePath.isEmpty()) {
                ZipEntry dirEntry = new ZipEntry(relativePath + (relativePath.endsWith("/") ? "" : "/"));
                zos.putNextEntry(dirEntry);
                zos.closeEntry();
            }
            File[] children = current.listFiles();
            if (children == null) {
                return true;
            }
            for (File child : children) {
                if (!addOpenclawDirectoryEntriesToZip(sourceDir, child, zos, buffer)) {
                    return false;
                }
            }
            return true;
        }

        String entryName = relativePath;
        ZipEntry fileEntry = new ZipEntry(entryName);
        zos.putNextEntry(fileEntry);
        try (FileInputStream input = new FileInputStream(current)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                zos.write(buffer, 0, read);
            }
        }
        zos.closeEntry();
        return true;
    }

    private boolean extractOpenclawBackupToDirectory(@NonNull File backupFile, @NonNull File homeDir) {
        byte[] buffer = new byte[OPENCLAW_BACKUP_IO_BUFFER_SIZE];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(backupFile)))) {
            ZipEntry entry;
            boolean restoredAny = false;
            String homePath = homeDir.getCanonicalPath();
            String expectedPrefix = homePath + File.separator;

            while ((entry = zis.getNextEntry()) != null) {
                String relativePath = normalizeOpenclawBackupEntryPath(entry.getName());
                if (relativePath == null) {
                    zis.closeEntry();
                    continue;
                }
                restoredAny = true;

                File targetFile = new File(homeDir, relativePath);
                String targetPath = targetFile.getCanonicalPath();
                if (!targetPath.equals(homePath) && !targetPath.startsWith(expectedPrefix)) {
                    Logger.logWarn(LOG_TAG, "Skipping unsafe backup entry: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory() || relativePath.endsWith("/")) {
                    if (!targetFile.exists() && !targetFile.mkdirs()) {
                        Logger.logWarn(LOG_TAG, "Failed to create directory from backup: " + targetFile.getAbsolutePath());
                        return false;
                    }
                    zis.closeEntry();
                    continue;
                }

                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    Logger.logWarn(LOG_TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
                    return false;
                }

                try (FileOutputStream output = new FileOutputStream(targetFile)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }

            return restoredAny;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private JSONObject readJsonFromFile(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return new JSONObject(sb.toString());
        } catch (IOException | org.json.JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to read JSON backup from " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private boolean writeJsonToFile(@NonNull File file, @NonNull JSONObject payload) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Failed to create parent directory: " + parent.getAbsolutePath());
                return false;
            }

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(payload.toString(2));
            }

            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            return true;
        } catch (IOException | org.json.JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to write restored OpenClaw file to " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    @Nullable
    private File getOpenclawHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, OPENCLAW_HOME_FOLDER);
    }

    private File getBotdropHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BOTDROP_HOME_FOLDER);
    }

    private File getOpenclawHomeParentDirectory() {
        File openclawDir = getOpenclawHomeDirectory();
        if (openclawDir == null || openclawDir.getParentFile() == null) {
            return null;
        }
        return openclawDir.getParentFile();
    }

    private boolean deleteRecursively(@NonNull File file) {
        if (!file.exists()) {
            return true;
        }

        Deque<File> stack = new ArrayDeque<>();
        Deque<File> orderedDelete = new ArrayDeque<>();
        stack.push(file);

        while (!stack.isEmpty()) {
            File current = stack.pop();
            if (!current.exists()) {
                continue;
            }
            orderedDelete.push(current);
            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) {
                    Logger.logWarn(LOG_TAG, "Failed to list children for " + current.getAbsolutePath());
                    return false;
                }
                for (File child : children) {
                    stack.push(child);
                }
            }
        }

        while (!orderedDelete.isEmpty()) {
            File target = orderedDelete.pop();
            if (!target.delete()) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    private String normalizeOpenclawBackupEntryPath(@Nullable String entryName) {
        if (entryName == null) {
            return null;
        }

        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../") || normalized.endsWith("/..") || normalized.contains("/../")) {
            return null;
        }

        String[] allowedRoots = new String[]{OPENCLAW_HOME_FOLDER, BOTDROP_HOME_FOLDER};
        int slashIndex = normalized.indexOf('/');
        String rootName = slashIndex >= 0 ? normalized.substring(0, slashIndex) : normalized;
        boolean isAllowedRoot = false;
        for (String root : allowedRoots) {
            if (root.equals(rootName)) {
                isAllowedRoot = true;
                break;
            }
        }

        if (!isAllowedRoot) {
            return null;
        }

        return normalized;
    }

    private void setButtonEnabled(TextView button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
    }

    /**
     * Create notification channel for gateway monitor service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BotDrop Gateway",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when BotDrop is running");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start the gateway monitor service
     */
    private void startGatewayMonitorService() {
        Intent serviceIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Start periodic status refresh
     */
    private void startStatusRefresh() {
        if (!mUiVisible) {
            return;
        }
        mStatusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (mUiVisible) {
                    refreshStatus();
                    mHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
                }
            }
        };
        mHandler.post(mStatusRefreshRunnable);
    }

    /**
     * Refresh gateway status and uptime
     */
    private void refreshStatus() {
        if (!mUiVisible) {
            return;
        }
        if (!mBound || mBotDropService == null) {
            return;
        }

        // Check if gateway is running
        mBotDropService.isGatewayRunning(result -> {
            if (!mUiVisible) {
                return;
            }
            boolean isRunning = result.success && result.stdout.trim().equals("running");
            updateStatusUI(isRunning);
            checkGatewayErrors(isRunning);

            // Get uptime if running
            if (isRunning) {
                mBotDropService.getGatewayUptime(uptimeResult -> {
                    if (!mUiVisible) {
                        return;
                    }
                    if (uptimeResult.success) {
                        String uptime = uptimeResult.stdout.trim();
                        if (!uptime.equals("—")) {
                            mUptimeText.setText(getString(R.string.botdrop_uptime, uptime));
                        } else {
                            mUptimeText.setText("—");
                        }
                    }
                });
            }
        });
    }

    /**
     * Update the status UI based on gateway state
     */
    private void updateStatusUI(boolean isRunning) {
        if (isRunning) {
            mStatusText.setText(getString(R.string.botdrop_gateway_running));
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_running);
            setButtonState(mStartButton, false, true);
            setButtonState(mStopButton, true, false);
            setButtonState(mRestartButton, true, true);
        } else {
            mStatusText.setText(getString(R.string.botdrop_gateway_stopped));
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_stopped);
            mUptimeText.setText("—");
            setButtonState(mStartButton, true, true);
            setButtonState(mStopButton, false, false);
            setButtonState(mRestartButton, false, true);
        }
    }

    private void setButtonState(View button, boolean enabled, boolean isFilled) {
        button.setEnabled(enabled);
        ImageView iconView = null;
        TextView labelView = null;
        int enabledColor = isFilled
            ? ContextCompat.getColor(this, R.color.botdrop_background)
            : ContextCompat.getColor(this, R.color.botdrop_accent);
        if (button == mStartButton) {
            iconView = mStartButtonIcon;
            labelView = mStartButtonLabel;
        } else if (button == mStopButton) {
            iconView = mStopButtonIcon;
            labelView = mStopButtonLabel;
        } else if (button == mRestartButton) {
            iconView = mRestartButtonIcon;
            labelView = mRestartButtonLabel;
        }
        if (enabled) {
            button.setAlpha(1.0f);
            if (labelView != null) labelView.setTextColor(enabledColor);
            if (iconView != null) iconView.setColorFilter(enabledColor);
        } else {
            button.setAlpha(0.5f);
            int color = ContextCompat.getColor(this, R.color.botdrop_secondary_text);
            if (labelView != null) labelView.setTextColor(color);
            if (iconView != null) iconView.setColorFilter(color);
        }
    }

    private void setAsAccessibleButton(View view) {
        if (view == null) {
            return;
        }
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    private void openChannelConfig(String platform) {
        Intent intent = new Intent(this, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_CHANNEL);
        if (!TextUtils.isEmpty(platform)) {
            intent.putExtra(SetupActivity.EXTRA_CHANNEL_PLATFORM, platform);
        }
        startActivity(intent);
    }

    private void showUpdateBanner(String latestVersion, String downloadUrl) {
        AnalyticsManager.logEvent(this, "app_update_banner_shown");
        mUpdateBannerText.setText(getString(R.string.botdrop_update_available_version, latestVersion));
        mUpdateBanner.setVisibility(View.VISIBLE);

        findViewById(R.id.btn_update_download).setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "app_update_download_tap");
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(browserIntent);
        });

        findViewById(R.id.btn_update_dismiss).setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "app_update_dismiss_tap");
            mUpdateBanner.setVisibility(View.GONE);
            UpdateChecker.dismiss(this, latestVersion);
        });
    }

    private void hideUpdateBanner() {
        if (mUpdateBanner != null) {
            mUpdateBanner.setVisibility(View.GONE);
        }
    }

    /**
     * Load channel configuration and update UI
     */
    private void loadChannelInfo() {
        mTelegramStatus.setText("○ —");
        mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        mDiscordStatus.setText("○ —");
        mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        if (mFeishuStatus != null) {
            mFeishuStatus.setText("○ —");
            mFeishuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        }
        if (mQQBotStatus != null) {
            mQQBotStatus.setText("○ —");
            mQQBotStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        }

        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null) {
                return;
            }

            if (ChannelSetupHelper.isTelegramConfigured(channels.optJSONObject("telegram"))) {
                mTelegramStatus.setText(getString(R.string.botdrop_connected));
                mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }

            if (ChannelSetupHelper.isDiscordConfigured(channels.optJSONObject("discord"))) {
                mDiscordStatus.setText(getString(R.string.botdrop_connected));
                mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }

            if (ChannelSetupHelper.isFeishuConfigured(channels.optJSONObject("feishu")) && mFeishuStatus != null) {
                mFeishuStatus.setText(getString(R.string.botdrop_connected));
                mFeishuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }

            if (ChannelSetupHelper.isQQBotConfigured(channels.optJSONObject("qqbot")) && mQQBotStatus != null) {
                mQQBotStatus.setText(getString(R.string.botdrop_connected));
                mQQBotStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load channel info: " + e.getMessage());
        }
    }

    /**
     * Start the gateway
     */
    private void startGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.botdrop_starting_gateway), Toast.LENGTH_SHORT).show();
        mStartButton.setEnabled(false);

        mBotDropService.startGateway(result -> {
            if (result.success) {
                AnalyticsManager.logEvent(this, "dashboard_start_success");
                Toast.makeText(this, getString(R.string.botdrop_gateway_started), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                AnalyticsManager.logEvent(this, "dashboard_start_failed");
                Toast.makeText(this, getString(R.string.botdrop_gateway_start_failed), Toast.LENGTH_SHORT).show();
                mStartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Start failed: " + result.stderr);
            }
        });
    }

    /**
     * Stop the gateway
     */
    private void stopGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.botdrop_stopping_gateway), Toast.LENGTH_SHORT).show();
        mStopButton.setEnabled(false);

        mBotDropService.stopGateway(result -> {
            if (result.success) {
                AnalyticsManager.logEvent(this, "dashboard_stop_success");
                Toast.makeText(this, getString(R.string.botdrop_gateway_stopped_toast), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                AnalyticsManager.logEvent(this, "dashboard_stop_failed");
                Toast.makeText(this, getString(R.string.botdrop_gateway_stop_failed), Toast.LENGTH_SHORT).show();
                mStopButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Stop failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway (for control button)
     */
    private void restartGatewayForControl() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.botdrop_gateway_restarting), Toast.LENGTH_SHORT).show();
        mRestartButton.setEnabled(false);

        mBotDropService.restartGateway(result -> {
            if (result.success) {
                AnalyticsManager.logEvent(this, "dashboard_restart_success");
                Toast.makeText(this, getString(R.string.botdrop_gateway_restarted), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                AnalyticsManager.logEvent(this, "dashboard_restart_failed");
                Toast.makeText(this, getString(R.string.botdrop_gateway_restart_failed), Toast.LENGTH_SHORT).show();
                mRestartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway (for model change)
     */
    private void restartGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.botdrop_gateway_restarting_with_new_model), Toast.LENGTH_SHORT).show();

        mBotDropService.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(this, getString(R.string.botdrop_gateway_restarted_successfully), Toast.LENGTH_SHORT).show();
                loadCurrentModel();
            } else {
                Toast.makeText(this, getString(R.string.botdrop_gateway_restart_failed), Toast.LENGTH_SHORT).show();
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
                loadCurrentModel();
            }
        });
    }

    /**
     * Load SSH connection info and display in the dashboard
     */
    private void loadSshInfo() {
        String ip = getDeviceIp();
        if (ip == null) ip = "<device-ip>";

        // Read SSH password from file
        String password = readSshPassword();
        if (password == null) password = "<not set>";

        mSshInfoText.setText(getString(R.string.botdrop_ssh_password_label, ip, password));
        mSshCard.setVisibility(View.VISIBLE);
    }

    private String readSshPassword() {
        try {
            java.io.File pwFile = new java.io.File(
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh_password");
            if (pwFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(pwFile));
                String password = reader.readLine();
                reader.close();
                if (password != null) return password.trim();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read SSH password: " + e.getMessage());
        }
        return null;
    }

    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to get device IP: " + e.getMessage());
        }
        return null;
    }

    /**
     * Open terminal activity
     */
    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }
    private void openAutomationPanel() {
        Intent intent = new Intent(this, AutomationPanelActivity.class);
        startActivity(intent);
    }

    private void startShizukuBridgeService() {
        Intent intent = new Intent(this, ShizukuBridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to start embedded Shizuku bridge: " + e.getMessage());
        }
    }

    private void openOpenclawWebUi() {
        if (!mUiVisible) {
            return;
        }

        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawWebUiOpening) {
            Toast.makeText(this, getString(R.string.botdrop_openclaw_web_ui_already_opening), Toast.LENGTH_SHORT).show();
            return;
        }
        mOpenclawWebUiOpening = true;
        setOpenclawWebUiButtonState(true, getString(R.string.botdrop_opening_web_ui));

        mBotDropService.isGatewayRunning(result -> {
            if (!mUiVisible) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                return;
            }

            if (result == null || !result.success || !"running".equals(result.stdout.trim())) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                Toast.makeText(this, getString(R.string.botdrop_openclaw_not_running), Toast.LENGTH_SHORT).show();
                return;
            }

            resolveOpenclawWebUiUrl(url -> {
                if (!mUiVisible) {
                    mOpenclawWebUiOpening = false;
                    setOpenclawWebUiButtonState(false, null);
                    return;
                }
                openOpenclawUrlWithReadinessCheck(url, 0);
            });
        });
    }

    private void openOpenclawUrlWithReadinessCheck(String webUiUrl, int attempt) {
        if (!mUiVisible) {
            mOpenclawWebUiOpening = false;
            setOpenclawWebUiButtonState(false, null);
            return;
        }

        final String url = TextUtils.isEmpty(webUiUrl) ? OPENCLAW_DEFAULT_WEB_UI_URL : webUiUrl.trim();
        if (TextUtils.isEmpty(url)) {
            mOpenclawWebUiOpening = false;
            setOpenclawWebUiButtonState(false, null);
            openOpenclawUrlInBrowser(OPENCLAW_DEFAULT_WEB_UI_URL);
            return;
        }

        new Thread(() -> {
            final boolean reachable = isOpenclawWebUiReachable(url);
            if (reachable || attempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                runOnUiThread(() -> {
                    mOpenclawWebUiOpening = false;
                    if (canShowDialog()) {
                        if (!mUiVisible) {
                            return;
                        }
                        if (!reachable && attempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                            Toast.makeText(
                                this,
                                getString(R.string.botdrop_web_ui_still_starting),
                                Toast.LENGTH_LONG
                            ).show();
                        }
                        openOpenclawUrlInBrowser(url);
                    }
                });
                return;
            }

            final int nextAttempt = attempt + 1;
            runOnUiThread(() -> setOpenclawWebUiButtonState(
                true,
                getString(R.string.botdrop_opening_web_ui_attempt, nextAttempt, OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT)
            ));
            mHandler.postDelayed(
                () -> openOpenclawUrlWithReadinessCheck(url, nextAttempt),
                OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS
            );
        }).start();
    }

    private boolean isOpenclawWebUiReachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            connection.disconnect();
            return code >= 200 && code < 600;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void resolveOpenclawWebUiUrl(OpenclawWebUiUrlCallback callback) {
        if (callback == null) return;
        if (mBotDropService == null) {
            callback.onUrlResolved(OPENCLAW_DEFAULT_WEB_UI_URL);
            return;
        }

        String configText = BotDropConfig.readConfig().toString();
        String gatewayToken = extractGatewayTokenFromConfig(configText);

        String host = "127.0.0.1";
        int port = OPENCLAW_DEFAULT_WEB_UI_PORT;
        String basePath = OPENCLAW_DEFAULT_WEB_UI_PATH;

        try {
            JSONObject config = new JSONObject(configText);
            String normalizedHost = extractOpenclawHostFromJson(config);
            int configPort = extractOpenclawPortFromJson(config);
            String configBasePath = extractOpenclawControlUiBasePathFromJson(config);
            if (!TextUtils.isEmpty(normalizedHost) && isLocalWebUiHost(normalizedHost)) {
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
        callback.onUrlResolved(appendGatewayTokenToWebUiUrl(baseUrl, gatewayToken));
    }

    private String extractOpenclawControlUiBasePathFromJson(JSONObject root) {
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

    private String normalizeOpenclawControlUiPath(String rawPath) {
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

    private String extractGatewayTokenFromText(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String candidate = extractOpenclawUrlFromText(text);
        if (TextUtils.isEmpty(candidate)) {
            // Keep scanning raw output in case the token is in a non-URL line.
        } else {
            try {
                String token = Uri.parse(candidate.trim()).getQueryParameter(OPENCLAW_WEB_UI_TOKEN_KEY);
                if (!TextUtils.isEmpty(token)) {
                    return token;
                }
            } catch (Exception ignored) {
            }
        }

        Matcher tokenMatcher = GATEWAY_TOKEN_QUERY_PATTERN.matcher(text);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1);
            if (!TextUtils.isEmpty(token)) {
                return token.trim();
            }
        }

        return null;
    }

    private String normalizeOpenclawDashboardUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        String trimmed = trimUrlPunctuation(rawUrl.trim());
        if (TextUtils.isEmpty(trimmed)) {
            return null;
        }

        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            String scheme = parsed.getScheme();
            if (TextUtils.isEmpty(scheme)) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            String normalizedHost = normalizeOpenclawHost(parsed.getHost());
            if (TextUtils.isEmpty(normalizedHost)) {
                return null;
            }
            if (!isLocalWebUiHost(normalizedHost)) {
                return null;
            }

            Uri.Builder normalizedBuilder = new Uri.Builder();
            normalizedBuilder.scheme(parsed.getScheme());
            String authority = normalizedHost;
            if (parsed.getPort() > 0) {
                authority = authority + ":" + parsed.getPort();
            }
            if (TextUtils.isEmpty(authority)) {
                return null;
            }
            normalizedBuilder.authority(authority);
            return normalizedBuilder.build().toString();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to normalize dashboard URL: " + e.getMessage());
            return null;
        }
    }

    private String normalizeOpenclawString(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.trim();
    }

    private String extractGatewayTokenFromConfig(String configText) {
        if (TextUtils.isEmpty(configText)) {
            return null;
        }

        try {
            JSONObject config = new JSONObject(configText);
            return extractGatewayTokenFromJson(config);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse OpenClaw config for gateway token: " + e.getMessage());
            return null;
        }
    }

    private String extractGatewayTokenFromJson(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject gateway = root.optJSONObject("gateway");
        if (gateway == null) {
            return null;
        }

        JSONObject auth = gateway.optJSONObject("auth");
        if (auth == null) {
            return null;
        }

        return normalizeOpenclawString(auth.optString("token", null));
    }

    private String chooseOpenclawWebUiUrl(String rawUrl) {
        String normalized = normalizeOpenclawWebUiUrl(rawUrl);
        return TextUtils.isEmpty(normalized) ? OPENCLAW_DEFAULT_WEB_UI_URL : normalized;
    }

    private String appendGatewayTokenToWebUiUrl(String webUiUrl, String token) {
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

    private boolean hasQueryToken(String url) {
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

    private String normalizeOpenclawWebUiUrl(String rawUrl) {
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

            StringBuilder url = new StringBuilder("http://").append(normalizedHost);
            if (port > 0) {
                url.append(':').append(port);
            }
            return url.toString();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to normalize OpenClaw URL: " + e.getMessage());
            return null;
        }
    }

    private String trimUrlPunctuation(String value) {
        if (TextUtils.isEmpty(value)) return value;
        return value.replaceAll("[\\)\\]\\}\\>,\\.;:\"]+$", "");
    }

    private String extractOpenclawUrlFromConfig(String configText) {
        if (TextUtils.isEmpty(configText)) return null;

        String fromText = extractOpenclawUrlFromText(configText);
        if (!TextUtils.isEmpty(fromText)) {
            String normalized = normalizeOpenclawWebUiUrl(fromText);
            if (!TextUtils.isEmpty(normalized)) return normalized;
        }

        try {
            JSONObject config = new JSONObject(configText);
            String host = extractOpenclawHostFromJson(config);
            int port = extractOpenclawPortFromJson(config);
            if (port <= 0) port = OPENCLAW_DEFAULT_WEB_UI_PORT;
            String normalizedHost = normalizeOpenclawHost(host);
            if (TextUtils.isEmpty(normalizedHost) || !isLocalWebUiHost(normalizedHost)) {
                normalizedHost = "127.0.0.1";
            }
            return "http://" + normalizedHost + ":" + port;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse OpenClaw config for web UI URL: " + e.getMessage());
        }

        return null;
    }

    private String extractOpenclawHostFromJson(JSONObject root) {
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

    private int extractOpenclawPortFromJson(JSONObject root) {
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

    private String parseHostFromText(String value) {
        String hostPort = extractHostPortFromText(value);
        if (TextUtils.isEmpty(hostPort)) return null;
        int separatorIndex = hostPort.lastIndexOf(':');
        if (separatorIndex <= 0) return null;
        return hostPort.substring(0, separatorIndex);
    }

    private int parsePortFromText(String value) {
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

    private String extractOpenclawUrlFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = WEB_UI_URL_PATTERN.matcher(text);
        String firstMatch = null;
        String bestMatch = null;
        while (matcher.find()) {
            String match = trimUrlPunctuation(matcher.group());
            if (TextUtils.isEmpty(match)) {
                continue;
            }
            if (firstMatch == null) {
                firstMatch = match;
            }
            if (bestMatch == null && isLikelyDashboardLink(match)) {
                bestMatch = match;
            }
        }
        return TextUtils.isEmpty(bestMatch) ? firstMatch : bestMatch;
    }

    private String extractHostPortFromText(String text) {
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

    private String extractOpenclawUrlFromLog(String logText) {
        String fromText = extractOpenclawUrlFromText(logText);
        if (!TextUtils.isEmpty(fromText)) {
            String normalized = normalizeOpenclawWebUiUrl(fromText);
            if (!TextUtils.isEmpty(normalized)) return normalized;
        }
        String hostPort = extractHostPortFromText(logText);
        if (TextUtils.isEmpty(hostPort)) return null;
        return normalizeOpenclawWebUiUrl(hostPort);
    }

    private int firstPositiveInt(int... values) {
        if (values == null) return -1;
        for (int value : values) {
            if (value > 0) return value;
        }
        return -1;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value.trim();
        }
        return null;
    }

    private String normalizeOpenclawHost(String host) {
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

    private boolean isLocalWebUiHost(String host) {
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

    private boolean isLikelyDashboardLink(String candidateUrl) {
        if (TextUtils.isEmpty(candidateUrl)) {
            return false;
        }
        String lower = candidateUrl.toLowerCase();
        if (lower.contains("openclaw.ai")) {
            return false;
        }
        try {
            Uri parsed = Uri.parse(candidateUrl.trim());
            String host = parsed.getHost();
            if (TextUtils.isEmpty(host)) {
                return false;
            }
            String normalizedHost = normalizeOpenclawHost(host);
            if (TextUtils.isEmpty(normalizedHost)) {
                return false;
            }
            String path = parsed.getPath();
            if (path != null && !path.isEmpty()) {
                String lowerPath = path.toLowerCase();
                if (lowerPath.contains("/docs") || lowerPath.contains("/documentation")) {
                    return false;
                }
            }
            return isLocalWebUiHost(normalizedHost);
        } catch (Exception e) {
            return false;
        }
    }

    private void openOpenclawUrlInBrowser(String url) {
        if (TextUtils.isEmpty(url)) {
            url = OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        try {
            Uri parsed = Uri.parse(url.trim());
            if (TextUtils.isEmpty(parsed.getScheme()) ||
                !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
                url = OPENCLAW_DEFAULT_WEB_UI_URL;
            }
        } catch (Exception ignored) {
            url = OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.botdrop_no_app_available_to_open_web_links), Toast.LENGTH_SHORT).show();
            Logger.logWarn(LOG_TAG, "No activity found for URL: " + url);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.botdrop_open_browser_error), Toast.LENGTH_SHORT).show();
            Logger.logWarn(LOG_TAG, "Failed to open URL: " + url + "; " + e.getMessage());
        }
    }

    /**
     * Load and display the current model from OpenClaw config
     */
    private void loadCurrentModel() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            String currentModel = null;

            JSONObject agents = config.optJSONObject("agents");
            if (agents != null) {
                JSONObject defaults = agents.optJSONObject("defaults");
                if (defaults != null) {
                    Object modelObj = defaults.opt("model");
                    if (modelObj instanceof JSONObject) {
                        currentModel = ((JSONObject) modelObj).optString("primary", null);
                    } else if (modelObj instanceof String) {
                        currentModel = (String) modelObj;
                    }
                }
            }

            if (TextUtils.isEmpty(currentModel)) {
                ConfigTemplate template = ConfigTemplateCache.loadTemplate(this);
                if (template != null && !TextUtils.isEmpty(template.model)) {
                    currentModel = template.model;
                }
            }

            if (!TextUtils.isEmpty(currentModel) && !"null".equals(currentModel)) {
                mCurrentModelText.setText(currentModel);
                Logger.logInfo(LOG_TAG, "Current model: " + currentModel);
            } else {
                mCurrentModelText.setText("—");
            }
        } catch (Exception e) {
            mCurrentModelText.setText("—");
            Logger.logError(LOG_TAG, "Failed to load current model: " + e.getMessage());
        }
    }

    /**
     * Show the model selector dialog
     */
    private void showModelSelector() {
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, getString(R.string.botdrop_service_unavailable_try_again), Toast.LENGTH_SHORT).show();
            return;
        }

        ModelSelectorDialog dialog = new ModelSelectorDialog(this, mBotDropService, true);
        dialog.show((provider, model, apiKey, baseUrl, availableModels) -> {
            if (provider != null && model != null) {
                String fullModel = provider + "/" + model;
                updateModel(fullModel, apiKey, baseUrl, availableModels);
            }
        });
    }

    /**
     * Update model/API key and restart gateway.
     */
    private void updateModel(String fullModel, String optionalApiKey, String optionalBaseUrl, List<String> availableModels) {
        if (!mBound || mBotDropService == null) {
            return;
        }

        mCurrentModelText.setText(getString(R.string.botdrop_updating_model));
        String[] parts = fullModel.split("/", 2);
        if (parts.length != 2) {
            Toast.makeText(this, getString(R.string.botdrop_invalid_model_format), Toast.LENGTH_SHORT).show();
            loadCurrentModel();
            return;
        }

        String provider = parts[0];
        String model = parts[1];
        boolean isCustomProvider = !TextUtils.isEmpty(optionalBaseUrl);
        if (isCustomProvider && (availableModels == null || availableModels.isEmpty())) {
            Toast.makeText(DashboardActivity.this, getString(R.string.botdrop_no_custom_model_list), Toast.LENGTH_SHORT).show();
            loadCurrentModel();
            return;
        }

        boolean configured = BotDropConfig.setActiveProvider(
            provider,
            model,
            optionalApiKey,
            isCustomProvider ? optionalBaseUrl : null,
            isCustomProvider ? availableModels : null
        );

        if (!configured) {
            Toast.makeText(DashboardActivity.this, getString(R.string.botdrop_failed_update_model_settings), Toast.LENGTH_SHORT).show();
            Logger.logError(LOG_TAG, "Failed to update model settings for " + fullModel);
            loadCurrentModel();
            return;
        }

        Logger.logInfo(LOG_TAG, "Model updated to: " + fullModel + ", apiKeyUpdated=" +
            (!TextUtils.isEmpty(optionalApiKey)));

        ConfigTemplate template = ConfigTemplateCache.loadTemplate(DashboardActivity.this);
        if (template == null) {
            template = new ConfigTemplate();
        }
        template.provider = provider;
        template.model = fullModel;
        if (!TextUtils.isEmpty(optionalApiKey)) {
            template.apiKey = optionalApiKey;
        }
        if (isCustomProvider && availableModels != null && !availableModels.isEmpty()) {
            template.customModels = new ArrayList<>(availableModels);
        } else if (!isCustomProvider) {
            template.customModels = null;
        }
        if (!TextUtils.isEmpty(optionalBaseUrl)) {
            template.baseUrl = optionalBaseUrl;
        } else {
            template.baseUrl = null;
        }
        ConfigTemplateCache.saveTemplate(DashboardActivity.this, template);

        restartGateway();
    }

    private void showOpenclawLog() {
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setEnabled(false);
        }

        View logDialogView = getLayoutInflater().inflate(R.layout.dialog_openclaw_log, null);
        TabLayout logTabLayout = logDialogView.findViewById(R.id.openclaw_log_tabs);
        ViewPager2 logViewPager = logDialogView.findViewById(R.id.openclaw_log_viewpager);
        Button copyButton = logDialogView.findViewById(R.id.openclaw_log_copy_button);
        Button closeButton = logDialogView.findViewById(R.id.openclaw_log_close_button);

        if (logTabLayout == null || logViewPager == null) {
            Toast.makeText(this, getString(R.string.botdrop_failed_to_read_openclaw_logs), Toast.LENGTH_SHORT).show();
            if (mOpenclawLogButton != null) {
                mOpenclawLogButton.setEnabled(true);
            }
            return;
        }

        final String[] logContents = {
            "Loading " + GATEWAY_LOG_LABEL + "...",
            "Loading " + GATEWAY_DEBUG_LOG_LABEL + "..."
        };
        final String noLogOutputText = getString(R.string.botdrop_no_log_output_available);

        RecyclerView.Adapter<OpenclawLogPageViewHolder> pagerAdapter = new RecyclerView.Adapter<OpenclawLogPageViewHolder>() {
            @NonNull
            @Override
            public OpenclawLogPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_openclaw_log_page, parent, false);
                OpenclawLogPageViewHolder holder = new OpenclawLogPageViewHolder(itemView);
                return holder;
            }

            @Override
            public void onBindViewHolder(@NonNull OpenclawLogPageViewHolder holder, int position) {
                String text = logContents[position];
                if (TextUtils.isEmpty(text)) {
                    text = noLogOutputText;
                }
                holder.bind(text);
            }

            @Override
            public int getItemCount() {
                return logContents.length;
            }
        };

        logViewPager.setAdapter(pagerAdapter);
        logViewPager.setOffscreenPageLimit(1);

        AlertDialog dialog = BotDropDialogStyler.createBuilder(this)
            .setView(logDialogView)
            .create();

        final boolean[] isLogDialogOpen = {true};
        final int[] pollBatchPending = {0};
        final int[] remainingFirstLoadCallbacks = {2};
        Runnable refreshLogs = new Runnable() {
            @Override
            public void run() {
                if (!isLogDialogOpen[0] || mBotDropService == null) {
                    return;
                }

                pollBatchPending[0] = 2;

                Runnable onPollCompleted = () -> {
                    int remaining = --pollBatchPending[0];
                    if (remainingFirstLoadCallbacks[0] > 0) {
                        int firstLoadRemaining = --remainingFirstLoadCallbacks[0];
                        if (firstLoadRemaining <= 0 && mOpenclawLogButton != null) {
                            mOpenclawLogButton.setEnabled(true);
                        }
                    }
                    if (remaining <= 0) {
                        if (isLogDialogOpen[0] && !isFinishing()) {
                            mHandler.postDelayed(this, OPENCLAW_LOG_TAIL_POLL_INTERVAL_MS);
                        }
                    }
                };

                mBotDropService.executeCommand(getOpenclawLogTailCommand(GATEWAY_LOG_FILE, GATEWAY_LOG_TAIL_LINES), result -> {
                    if (isFinishing() || !isLogDialogOpen[0]) {
                        onPollCompleted.run();
                        return;
                    }
                    String logText = getFormattedLogResult(result, GATEWAY_LOG_LABEL);
                    logContents[0] = logText;
                    pagerAdapter.notifyItemChanged(0);
                    onPollCompleted.run();
                });

                mBotDropService.executeCommand(getOpenclawLogTailCommand(GATEWAY_DEBUG_LOG_FILE, GATEWAY_DEBUG_LOG_TAIL_LINES), result -> {
                    if (isFinishing() || !isLogDialogOpen[0]) {
                        onPollCompleted.run();
                        return;
                    }
                    String logText = getFormattedLogResult(result, GATEWAY_DEBUG_LOG_LABEL);
                    logContents[1] = logText;
                    pagerAdapter.notifyItemChanged(1);
                    onPollCompleted.run();
                });
            }
        };

        refreshLogs.run();

        new com.google.android.material.tabs.TabLayoutMediator(logTabLayout, logViewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText(GATEWAY_LOG_LABEL);
            } else {
                tab.setText(GATEWAY_DEBUG_LOG_LABEL);
            }
        }).attach();

        copyButton.setOnClickListener(v -> {
            int currentItem = logViewPager.getCurrentItem();
            String copyTarget = noLogOutputText;
            if (currentItem >= 0 && currentItem < logContents.length) {
                copyTarget = logContents[currentItem];
            }
            if (TextUtils.isEmpty(copyTarget)) {
                copyTarget = noLogOutputText;
            }
            copyToClipboard(copyTarget);
        });

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            isLogDialogOpen[0] = false;
            mHandler.removeCallbacks(refreshLogs);
            if (mOpenclawLogButton != null) {
                mOpenclawLogButton.setEnabled(true);
            }
        });

        dialog.show();
        BotDropDialogStyler.applyTransparentCardWindow(dialog);
    }

    private static class OpenclawLogPageViewHolder extends RecyclerView.ViewHolder {
        private final ScrollView mLogScrollView;
        private final TextView mLogTextView;
        private View.OnLayoutChangeListener mPendingScrollRestoreListener;

        OpenclawLogPageViewHolder(@NonNull View itemView) {
            super(itemView);
            mLogScrollView = itemView instanceof ScrollView ? (ScrollView) itemView : null;
            mLogTextView = itemView.findViewById(R.id.openclaw_log_page_text);
            if (mLogTextView != null) {
                mLogTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
            }
        }

        void bind(String text) {
            if (mLogTextView != null) {
                setLogTextPreservingScroll(text == null ? "" : text);
            }
        }

        private void setLogTextPreservingScroll(@NonNull String text) {
            if (mLogScrollView == null) {
                mLogTextView.setText(text);
                return;
            }

            int previousScrollY = mLogScrollView.getScrollY();
            boolean wasAtBottom = isScrolledToBottom(mLogScrollView);

            if (mPendingScrollRestoreListener != null) {
                mLogTextView.removeOnLayoutChangeListener(mPendingScrollRestoreListener);
            }

            mPendingScrollRestoreListener = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mLogTextView.removeOnLayoutChangeListener(this);
                    if (mPendingScrollRestoreListener == this) {
                        mPendingScrollRestoreListener = null;
                    }
                    mLogScrollView.post(() -> {
                        int targetScrollY = wasAtBottom
                            ? getScrollBottom(mLogScrollView)
                            : Math.min(previousScrollY, getScrollBottom(mLogScrollView));
                        mLogScrollView.scrollTo(0, targetScrollY);
                    });
                }
            };

            mLogTextView.addOnLayoutChangeListener(mPendingScrollRestoreListener);
            mLogTextView.setText(text);
        }
    }

    private static boolean isScrolledToBottom(@NonNull ScrollView scrollView) {
        return scrollView.getScrollY() >= getScrollBottom(scrollView);
    }

    private static int getScrollBottom(@NonNull ScrollView scrollView) {
        View child = scrollView.getChildAt(0);
        if (child == null) {
            return 0;
        }
        return Math.max(0, child.getBottom() - scrollView.getHeight());
    }

    private String getFormattedLogResult(@Nullable BotDropService.CommandResult result, String logLabel) {
        String logText = null;
        if (result == null) {
            logText = getString(R.string.botdrop_failed_to_read_openclaw_logs);
        }
        if (logText == null) {
            if (!result.success) {
                StringBuilder fallback = new StringBuilder();
                if (!TextUtils.isEmpty(result.stderr)) {
                    fallback.append(result.stderr.trim());
                }
                if (!TextUtils.isEmpty(result.stdout)) {
                    if (fallback.length() > 0) {
                        fallback.append("\n\n");
                    }
                    fallback.append(result.stdout.trim());
                }
                logText = fallback.toString();
                if (TextUtils.isEmpty(logText)) {
                    logText = getString(R.string.botdrop_failed_to_read_openclaw_logs_exit_code, result.exitCode);
                }
            } else {
                logText = result.stdout;
            }
            if (TextUtils.isEmpty(logText)) {
                logText = getString(R.string.botdrop_failed_to_read_openclaw_logs_exit_code, result.exitCode);
            }
        }

        if (TextUtils.isEmpty(logText)) {
            logText = getString(R.string.botdrop_no_log_output_available);
        }
        return logLabel + "\n" + logText;
    }

    private void copyToClipboard(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, getString(R.string.botdrop_clipboard_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        String textToCopy = content == null ? "" : content;
        ClipData clip = ClipData.newPlainText("OpenClaw Gateway Log", textToCopy);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, getString(R.string.botdrop_log_copied), Toast.LENGTH_SHORT).show();
    }

    // --- OpenClaw update ---

    private void showOpenclawVersionManagerDialog() {
        if (mOpenclawVersionActionInProgress) {
            return;
        }
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        setOpenclawVersionManagerBusy(true);
        dismissOpenclawUpdateDialog();
        if (mOpenclawVersionManagerDialog != null) {
            mOpenclawVersionManagerDialog.dismiss();
            mOpenclawVersionManagerDialog = null;
        }

        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(getString(R.string.botdrop_openclaw_versions))
            .setMessage(getString(R.string.botdrop_loading_versions))
            .setCancelable(false)
            .setNegativeButton(R.string.botdrop_cancel, (d, w) -> setOpenclawVersionManagerBusy(false))
            .create();
        mOpenclawVersionManagerDialog.show();

        fetchOpenclawVersions((versions, errorMessage) -> {
                if (isFinishing() || isDestroyed()) {
                    setOpenclawVersionManagerBusy(false);
                    return;
                }
                if (mOpenclawVersionManagerDialog != null) {
                    mOpenclawVersionManagerDialog.dismiss();
                    mOpenclawVersionManagerDialog = null;
                }

                if (versions == null || versions.isEmpty()) {
                    showOpenclawVersionManagerErrorDialog(
                        TextUtils.isEmpty(errorMessage) ? getString(R.string.botdrop_no_versions_available) : errorMessage
                    );
                    return;
                }

                showOpenclawVersionListDialog(versions);
            }
        );
    }

    private void showOpenclawVersionManagerErrorDialog(String message) {
        if (!canShowDialog()) return;
        if (TextUtils.isEmpty(message)) {
            message = getString(R.string.botdrop_failed_to_load_version_list);
        }

        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(getString(R.string.botdrop_openclaw_versions))
            .setMessage(message)
            .setNegativeButton(R.string.botdrop_close, (d, w) -> setOpenclawVersionManagerBusy(false))
            .setPositiveButton(R.string.botdrop_retry, (d, w) -> showOpenclawVersionManagerDialog())
            .setOnDismissListener(d -> setOpenclawVersionManagerBusy(false))
            .create();
        mOpenclawVersionManagerDialog.show();
    }

    private void showOpenclawVersionListDialog(List<String> versions) {
        if (!canShowDialog()) return;
        final List<String> normalized = OpenclawVersionUtils.normalizeVersionList(versions);
        if (normalized.isEmpty()) {
            showOpenclawVersionManagerErrorDialog(getString(R.string.botdrop_no_valid_versions_found));
            return;
        }

        String currentVersion = OpenclawVersionUtils.normalizeForSort(BotDropService.getOpenclawVersion());
        String[] labels = new String[normalized.size()];
        for (int i = 0; i < normalized.size(); i++) {
            String v = normalized.get(i);
            if (!TextUtils.isEmpty(currentVersion) && TextUtils.equals(currentVersion, v)) {
                labels[i] = getString(R.string.botdrop_openclaw_current_version, v);
            } else {
                labels[i] = getString(R.string.botdrop_openclaw_version, v);
            }
        }

        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(getString(R.string.botdrop_openclaw_versions))
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= normalized.size()) {
                    setOpenclawVersionManagerBusy(false);
                    return;
                }
                showOpenclawVersionInstallConfirm(normalized.get(which));
            })
            .setNegativeButton(R.string.botdrop_close, (d, w) -> setOpenclawVersionManagerBusy(false))
            .create();
        mOpenclawVersionManagerDialog.show();
    }

    private void showOpenclawVersionInstallConfirm(String version) {
        if (!canShowDialog()) return;
        String installVersion = OpenclawVersionUtils.normalizeInstallVersion(version);
        if (TextUtils.isEmpty(installVersion)) {
            setOpenclawVersionManagerBusy(false);
            Toast.makeText(this, getString(R.string.botdrop_invalid_version_format), Toast.LENGTH_SHORT).show();
            return;
        }

        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(getString(R.string.botdrop_install) + " " + getString(R.string.botdrop_openclaw))
            .setMessage(getString(R.string.botdrop_install_openclaw_confirm, installVersion))
            .setCancelable(false)
            .setPositiveButton(R.string.botdrop_install, (d, w) -> {
                setOpenclawVersionManagerBusy(true);
                startOpenclawUpdate(installVersion);
            })
            .setNegativeButton(R.string.botdrop_cancel, (d, w) -> setOpenclawVersionManagerBusy(false))
            .setOnDismissListener(d -> setOpenclawVersionManagerBusy(false))
            .create();
        mOpenclawVersionManagerDialog.show();
    }

    private void fetchOpenclawVersions(OpenclawVersionUtils.VersionListCallback cb) {
        if (cb == null) {
            return;
        }
        String currentVersion = BotDropService.getOpenclawVersion();

        mBotDropService.executeCommand(
            OpenclawVersionUtils.VERSIONS_COMMAND,
            OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS,
            result -> {
            if (result == null || !result.success) {
                String fallbackError = result == null
                    ? getString(R.string.botdrop_failed_to_fetch_versions)
                    : getString(R.string.botdrop_failed_to_fetch_versions_exit, String.valueOf(result.exitCode));
                cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), fallbackError);
                return;
            }

            List<String> versions = OpenclawVersionUtils.parseVersions(result.stdout);
            if (versions.isEmpty()) {
                cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), getString(R.string.botdrop_no_versions_found));
                return;
            }
            cb.onResult(versions, null);
            });
    }

    private void setOpenclawVersionManagerBusy(boolean isBusy) {
        mOpenclawVersionActionInProgress = isBusy;
        if (mOpenclawCheckUpdateButton != null) {
            mOpenclawCheckUpdateButton.setEnabled(!isBusy);
        }
    }

    private void checkOpenclawUpdate() {
        if (!mBound || mBotDropService == null) return;

        // One-time migration: clear stale throttle from previous code that recorded
        // check time even when npm returned invalid output, blocking retries for 24h.
        android.content.SharedPreferences updatePrefs =
            getSharedPreferences("openclaw_update", MODE_PRIVATE);
        if (!updatePrefs.getBoolean("throttle_fix_v1", false)) {
            updatePrefs.edit()
                .remove("last_check_time")
                .putBoolean("throttle_fix_v1", true)
                .apply();
        }

        // Display current version
        String currentVersion = BotDropService.getOpenclawVersion();
        if (currentVersion != null && mOpenclawVersionText != null) {
            mOpenclawVersionText.setText(getString(R.string.botdrop_openclaw_version, currentVersion));
        }

        // Also check stored result immediately (in case a previous check found an update)
        String[] stored = OpenClawUpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showOpenclawUpdateDialog(stored[0], stored[1], false);
        }

        // Run throttled check
        OpenClawUpdateChecker.check(this, mBotDropService, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_available_auto");
                showOpenclawUpdateDialog(current, latest, false);
            }

            @Override
            public void onNoUpdate() {
                AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_none_auto");
                dismissOpenclawUpdateDialog();
            }
        });
    }

    private void forceCheckOpenclawUpdate() {
        if (!mBound || mBotDropService == null) {
            AnalyticsManager.logEvent(this, "openclaw_update_check_blocked");
            Toast.makeText(this, getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawCheckUpdateButton == null) {
            Toast.makeText(this, getString(R.string.botdrop_check_button_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }

        mOpenclawCheckUpdateButton.setEnabled(false);
        mOpenclawCheckUpdateButton.setText(getString(R.string.botdrop_checking_openclaw));
        mOpenclawLatestUpdateVersion = null;
        mOpenclawManualCheckRequested = true;

        OpenClawUpdateChecker.check(this, mBotDropService, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_available_manual");
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText(getString(R.string.botdrop_check_openclaw_updates));
                mOpenclawManualCheckRequested = false;
                showOpenclawUpdateDialog(current, latest, true);
            }

            @Override
            public void onNoUpdate() {
                AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_none_manual");
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText(getString(R.string.botdrop_check_openclaw_updates));
                mOpenclawManualCheckRequested = false;
                dismissOpenclawUpdateDialog();
                Toast.makeText(DashboardActivity.this, getString(R.string.botdrop_already_up_to_date), Toast.LENGTH_SHORT).show();
            }
        }, true);
    }

    private void showOpenclawUpdateDialog(String currentVersion, String latestVersion, boolean manualCheck) {
        if (TextUtils.isEmpty(latestVersion) || isFinishing() || isDestroyed()) {
            return;
        }
        if ((mOpenclawVersionManagerDialog != null && mOpenclawVersionManagerDialog.isShowing())
            || mOpenclawVersionActionInProgress) {
            return;
        }

        if (!manualCheck && TextUtils.equals(latestVersion, mOpenclawLatestUpdateVersion)) {
            return;
        }
        if (mOpenclawUpdateDialog != null && mOpenclawUpdateDialog.isShowing()) {
            return;
        }

        mOpenclawLatestUpdateVersion = latestVersion;
        String currentPart = TextUtils.isEmpty(currentVersion) ? getString(R.string.botdrop_unknown) : currentVersion;
        String content = getString(R.string.botdrop_openclaw_update_available, currentPart, latestVersion);

        dismissOpenclawUpdateDialog();
        final String updateVersion = latestVersion;
        final String analyticsSource = manualCheck ? "manual" : "auto";
        mOpenclawUpdateDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(getString(R.string.botdrop_update_available))
            .setMessage(content)
            .setCancelable(true)
            .setPositiveButton(R.string.botdrop_update, (d, w) -> {
                AnalyticsManager.logEvent(this, "openclaw_update_accept_tap", "source", analyticsSource);
                startOpenclawUpdate(updateVersion);
            })
            .setNeutralButton(R.string.botdrop_later, (d, w) ->
                AnalyticsManager.logEvent(this, "openclaw_update_later_tap", "source", analyticsSource))
            .setNegativeButton(R.string.botdrop_dismiss, (d, w) -> {
                AnalyticsManager.logEvent(this, "openclaw_update_dismiss_tap", "source", analyticsSource);
                dismissOpenclawUpdate(updateVersion);
            })
            .setOnDismissListener(dialog -> {
                if (mOpenclawUpdateDialog == dialog) {
                    mOpenclawUpdateDialog = null;
                    mOpenclawManualCheckRequested = false;
                }
            })
            .create();
        mOpenclawUpdateDialog.show();
        AnalyticsManager.logEvent(this, "openclaw_update_dialog_shown", "source", analyticsSource);
        if (mOpenclawManualCheckRequested) {
            mOpenclawManualCheckRequested = false;
        }
    }

    private void openBotdropWebsite() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://botdrop.app/"));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.botdrop_no_browser_available), Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissOpenclawUpdate(String version) {
        if (!TextUtils.isEmpty(version)) {
            OpenClawUpdateChecker.dismiss(this, version);
            Toast.makeText(this, getString(R.string.botdrop_dismissed_update, version), Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissOpenclawUpdateDialog() {
        if (mOpenclawUpdateDialog != null && mOpenclawUpdateDialog.isShowing()) {
            mOpenclawUpdateDialog.dismiss();
        }
        mOpenclawUpdateDialog = null;
    }

    private void startOpenclawUpdate(String targetVersion) {
        if (TextUtils.isEmpty(targetVersion)) {
            Toast.makeText(this, getString(R.string.botdrop_no_update_target_version), Toast.LENGTH_SHORT).show();
            setOpenclawVersionManagerBusy(false);
            return;
        }

        dismissOpenclawUpdateDialog();
        setOpenclawVersionManagerBusy(true);
        if (!mBound || mBotDropService == null) {
            setOpenclawVersionManagerBusy(false);
            return;
        }
        if (mOpenclawVersionManagerDialog != null) {
            mOpenclawVersionManagerDialog.dismiss();
            mOpenclawVersionManagerDialog = null;
        }
        AnalyticsManager.logEvent(this, "openclaw_update_started");

        // Build step-based progress dialog
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_openclaw_update, null);
        TextView[] stepIcons = {
            dialogView.findViewById(R.id.update_step_0_icon),
            dialogView.findViewById(R.id.update_step_1_icon),
            dialogView.findViewById(R.id.update_step_2_icon),
            dialogView.findViewById(R.id.update_step_3_icon),
            dialogView.findViewById(R.id.update_step_4_icon),
        };
        TextView[] stepPercents = {
            dialogView.findViewById(R.id.update_step_0_percent),
            dialogView.findViewById(R.id.update_step_1_percent),
            dialogView.findViewById(R.id.update_step_2_percent),
            dialogView.findViewById(R.id.update_step_3_percent),
            dialogView.findViewById(R.id.update_step_4_percent),
        };
        TextView statusMessage = dialogView.findViewById(R.id.update_status_message);

        AlertDialog progressDialog = BotDropDialogStyler.createBuilder(this)
            .setTitle(R.string.botdrop_updating_openclaw)
            .setView(dialogView)
            .setCancelable(false)
            .create();
        progressDialog.show();
        BotDropDialogStyler.applyTransparentCardWindow(progressDialog);

        // Disable control buttons during update
        mStartButton.setEnabled(false);
        mStopButton.setEnabled(false);
        mRestartButton.setEnabled(false);

        mBotDropService.updateOpenclaw(targetVersion,
            new BotDropService.UpdateProgressCallback() {
            private int currentStep = -1;

            private void advanceTo(String message) {
                int nextStep = OpenclawUpdateProgress.resolveStepFromMessage(message);
                if (nextStep < 0) return;
                advanceToStep(nextStep);
            }

            private void advanceToStep(int nextStep) {
                if (nextStep < 0) return;

                // Mark all previous steps as complete
                for (int i = 0; i < nextStep && i < stepIcons.length; i++) {
                    stepIcons[i].setText("\u2713");
                    stepPercents[i].setText(StepPercentUtils.formatPercent(100));
                }
                // Mark current step as in-progress
                if (nextStep < stepIcons.length) {
                    stepIcons[nextStep].setText("\u25CF");
                    if (nextStep > currentStep) {
                        stepPercents[nextStep].setText(StepPercentUtils.formatPercent(0));
                    }
                }
                currentStep = nextStep;
            }

            @Override
            public void onStepStart(String message) {
                int nextStep = OpenclawUpdateProgress.resolveStepFromMessage(message);
                advanceTo(message);
                if (nextStep >= 0 && nextStep < stepPercents.length) {
                    stepPercents[nextStep].setText(
                        StepPercentUtils.formatPercent(
                            StepPercentUtils.extractPercent(message, 0)
                        )
                    );
                }
            }

            @Override
            public void onError(String error) {
                AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_failed");
                progressDialog.dismiss();
                setOpenclawVersionManagerBusy(false);
                refreshStatus();
                if (canShowDialog()) {
                    BotDropDialogStyler.createBuilder(DashboardActivity.this)
                        .setTitle(R.string.botdrop_update_failed)
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
                checkOpenclawUpdate();
            }

            @Override
            public void onComplete(String newVersion) {
                mOpenclawLatestUpdateVersion = null;
                advanceToStep(OpenclawUpdateProgress.STEP_REFRESHING_MODELS);
                statusMessage.setText(getString(R.string.botdrop_updated_to_version_refreshing, newVersion));
                prefetchModelsForUpdate(newVersion, success -> {
                    AnalyticsManager.logEvent(DashboardActivity.this, "openclaw_update_completed");
                    // Mark all steps complete
                    for (int i = 0; i < stepIcons.length; i++) {
                        stepIcons[i].setText("\u2713");
                        stepPercents[i].setText(StepPercentUtils.formatPercent(100));
                    }
                    statusMessage.setText(
                        success
                            ? getString(R.string.botdrop_updated_to_version, newVersion)
                            : getString(R.string.botdrop_updated_to_version_cache_failed, newVersion)
                    );

                    // Auto-dismiss after 1.5s
                    mHandler.postDelayed(() -> {
                        if (canShowDialog()) {
                            progressDialog.dismiss();
                        }
                        setOpenclawVersionManagerBusy(false);
                        OpenClawUpdateChecker.clearUpdate(DashboardActivity.this);
                        if (mOpenclawVersionText != null) {
                            mOpenclawVersionText.setText(getString(R.string.botdrop_openclaw_version, newVersion));
                        }
                        refreshStatus();
                    }, 1500);
                });
            }
        });
    }

    private void prefetchModelsForUpdate(String openclawVersion, ModelListPrefetchCallback callback) {
        final ModelListPrefetchCallback finalCallback = callback == null ? (ModelListPrefetchCallback) success -> {} : callback;

        if (mBotDropService == null) {
            finalCallback.onFinished(false);
            return;
        }

        final String normalizedVersion = normalizeModelCacheKey(openclawVersion);
        mBotDropService.executeCommand(MODEL_LIST_COMMAND, result -> {
            if (!result.success) {
                Logger.logWarn(LOG_TAG, "Model list prefetch failed for v" + openclawVersion + ": exit " + result.exitCode);
                finalCallback.onFinished(false);
                return;
            }

            List<ModelInfo> models = parseModelListForUpdate(result.stdout);
            if (models.isEmpty()) {
                Logger.logWarn(LOG_TAG, "Model list prefetch returned empty output for v" + openclawVersion);
                finalCallback.onFinished(false);
                return;
            }

            Collections.sort(models, (a, b) -> {
                if (a == null || b == null || a.fullName == null || b.fullName == null) return 0;
                return b.fullName.compareToIgnoreCase(a.fullName);
            });

            cacheModelsForUpdate(normalizedVersion, models);
            finalCallback.onFinished(true);
            Logger.logInfo(LOG_TAG, "Prefetched " + models.size() + " models for OpenClaw v" + openclawVersion);
        });
    }

    private List<ModelInfo> parseModelListForUpdate(String output) {
        List<ModelInfo> models = new ArrayList<>();
        if (TextUtils.isEmpty(output)) {
            return models;
        }

        try {
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("Model ")) {
                    continue;
                }

                String token = trimmed;
                if (trimmed.contains(" ")) {
                    token = trimmed.split("\\s+")[0];
                }

                if (isModelTokenForUpdate(token)) {
                    models.add(new ModelInfo(token));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to parse model list output: " + e.getMessage());
        }
        return models;
    }

    private void cacheModelsForUpdate(String version, List<ModelInfo> models) {
        if (TextUtils.isEmpty(version) || models == null || models.isEmpty()) return;

        try {
            JSONArray list = new JSONArray();
            for (ModelInfo model : models) {
                if (model != null && !TextUtils.isEmpty(model.fullName)) {
                    list.put(model.fullName);
                }
            }

            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("updated_at", System.currentTimeMillis());
            root.put("models", list);

            getSharedPreferences(MODEL_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(modelCacheKey(version), root.toString())
                .apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cache prefetched model list: " + e.getMessage());
        }
    }

    private String modelCacheKey(String version) {
        return MODEL_CACHE_KEY_PREFIX + normalizeModelCacheKey(version);
    }

    private String normalizeModelCacheKey(String version) {
        if (TextUtils.isEmpty(version)) {
            return "unknown";
        }
        return version.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isModelTokenForUpdate(String token) {
        if (token == null || token.isEmpty()) return false;
        if (!token.contains("/")) return false;
        return token.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+");
    }

    private void checkGatewayErrors(boolean isRunning) {
        if (!mBound || mBotDropService == null || !isRunning) {
            showGatewayError(null);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastErrorCheckAtMs < ERROR_CHECK_INTERVAL_MS) {
            return;
        }
        mLastErrorCheckAtMs = now;

        mBotDropService.executeCommand(
            "if [ -f ~/.openclaw/gateway.log ]; then tail -n 120 ~/.openclaw/gateway.log; fi",
            result -> {
                if (!result.success) {
                    Logger.logWarn(LOG_TAG, "Failed to read gateway.log: " + result.stderr);
                    return;
                }
                String errorLine = extractRecentGatewayError(result.stdout);
                showGatewayError(errorLine);
            }
        );
    }

    private String extractRecentGatewayError(String logText) {
        if (TextUtils.isEmpty(logText)) return null;

        String[] lines = logText.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String raw = lines[i];
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            String lower = line.toLowerCase();
            boolean looksLikeError =
                lower.contains(" sendmessage failed") ||
                lower.contains(" sendchataction failed") ||
                lower.contains(" fetch failed") ||
                lower.contains("error:") ||
                lower.contains("exception") ||
                lower.contains("unhandled rejection") ||
                lower.contains("network request for");
            if (looksLikeError) {
                if (line.length() > 180) {
                    line = line.substring(0, 180) + "...";
                }
                return line;
            }
        }
        return null;
    }

    private void showGatewayError(String message) {
        if (TextUtils.equals(message, mLastErrorMessage)) {
            return;
        }
        mLastErrorMessage = message;

        if (TextUtils.isEmpty(message)) {
            mGatewayErrorBanner.setVisibility(View.GONE);
            mGatewayErrorText.setText("—");
        } else {
            mGatewayErrorText.setText(message);
            mGatewayErrorBanner.setVisibility(View.VISIBLE);
        }
    }
}
