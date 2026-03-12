package app.botdrop;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.appcompat.app.AlertDialog;

import app.botdrop.shizuku.ShizukuBridgeService;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.google.android.material.tabs.TabLayout;

/**
 * Dashboard activity - main screen after setup is complete.
 * Shows gateway status, connected channels, and control buttons.
 * Auto-refreshes status every 5 seconds.
 */
public class DashboardActivity extends FragmentActivity {

    //========================================================================================
    // [SECTION] Core constants and state
    //========================================================================================

    private static final String LOG_TAG = "DashboardActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_gateway";
    private static final int STATUS_REFRESH_INTERVAL_MS = 5000; // 5 seconds
    private static final int ERROR_CHECK_INTERVAL_MS = 15000; // 15 seconds

    private Button mStartButton;
    private Button mStopButton;
    private Button mRestartButton;
    private TabLayout mRuntimeModeTabs;
    private View mSshCard;
    private TextView mSshInfoText;
    private View mUpdateBanner;
    private TextView mUpdateBannerText;
    private TextView mOpenclawVersionText;
    private TextView mOpenclawCheckUpdateButton;
    private TextView mOpenclawWebUiButton;
    private TextView mOpenclawLogButton;
    private TextView mOpenclawBackupButton;
    private TextView mOpenclawRestoreButton;
    private Button mOpenAutomationPanelButton;
    private ImageButton mBackToAgentSelectionButton;
    private OpenclawUpdateController mOpenclawUpdateController;
    private OpenclawLogController mOpenclawLogController;
    private OpenclawWebUiController mOpenclawWebUiController;
    private OpenclawConfigController mOpenclawConfigController;
    private DashboardUpdateBannerController mUpdateBannerController;
    private DashboardNetworkInfoController mNetworkInfoController;
    private static final String RUNTIME_MODE_FRAGMENT_TAG = "runtime_mode_fragment";
    private boolean mUiVisible = true;

    private enum RuntimeMode {
        GATEWAY,
        NODE
    }

    private RuntimeMode mRuntimeMode = RuntimeMode.GATEWAY;
    private final DashboardRuntimeModeSection mGatewayRuntimeModeSection = new GatewayDashboardModeFragment();
    private final DashboardRuntimeModeSection mNodeRuntimeModeSection = new NodeDashboardModeFragment();
    private DashboardRuntimeModeSection mCurrentRuntimeModeSection;

    private BotDropService mBotDropService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;
    private long mLastErrorCheckAtMs = 0L;
    private OnBackInvokedCallback mOnBackInvokedCallback;
    //========================================================================================
    // [SECTION END] Core constants and state
    //========================================================================================

    //========================================================================================
    // [SECTION] Service connection and lifecycle bridge
    //========================================================================================
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
            startGatewayMonitorService(mRuntimeMode);

            // Keep embedded Shizuku bridge warm for openclaw/command fallback path
            startShizukuBridgeService();

            // Load runtime mode specific UI state
            notifyRuntimeModeSectionServiceConnected();

            // Check for OpenClaw updates
            if (mOpenclawUpdateController != null) {
                mOpenclawUpdateController.checkOpenclawUpdate();
            }

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mBotDropService = null;
            notifyRuntimeModeSectionServiceDisconnected();
            updateRuntimeStatus(false, null);
            clearCurrentRuntimeError();
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };
    //========================================================================================
    // [SECTION END] Service connection and lifecycle bridge
    //========================================================================================

    //========================================================================================
    // [SECTION] Lifecycle methods
    //========================================================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_dashboard);
        Logger.logDebug(LOG_TAG, "onCreate start");

        //================================================================================
        // [SUBSECTION] Dashboard initialization
        //================================================================================

        // Create notification channel
        createNotificationChannel();

        //--------------------------------------------------------------------------------
        // [SUBSECTION] UI binding and core controls
        //--------------------------------------------------------------------------------
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mRestartButton = findViewById(R.id.btn_restart);
        mRuntimeModeTabs = findViewById(R.id.runtime_mode_tabs);
        Button openTerminalButton = findViewById(R.id.btn_open_terminal);

        // Setup button listeners
        if (mRuntimeModeTabs != null) {
            if (mRuntimeModeTabs.getTabCount() == 0) {
                mRuntimeModeTabs.addTab(mRuntimeModeTabs.newTab().setText(getString(R.string.botdrop_runtime_mode_gateway)));
                mRuntimeModeTabs.addTab(mRuntimeModeTabs.newTab().setText(getString(R.string.botdrop_runtime_mode_node)));
            }
            mRuntimeModeTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    if (tab == null) {
                        return;
                    }
                    RuntimeMode selectedMode = tab.getPosition() == 1
                        ? RuntimeMode.NODE
                        : RuntimeMode.GATEWAY;
                    setRuntimeMode(selectedMode);
                }

                @Override
                public void onTabUnselected(TabLayout.Tab tab) {
                }

                @Override
                public void onTabReselected(TabLayout.Tab tab) {
                    onTabSelected(tab);
                }
            });
        }

        mStartButton.setOnClickListener(v -> startCurrentRuntime());
        mStopButton.setOnClickListener(v -> stopCurrentRuntime());
        mRestartButton.setOnClickListener(v -> restartCurrentRuntimeForControl());
        openTerminalButton.setOnClickListener(v -> openTerminal());
        mOpenAutomationPanelButton = findViewById(R.id.btn_open_automation_panel);
        if (mOpenAutomationPanelButton != null) {
            mOpenAutomationPanelButton.setOnClickListener(v -> openAutomationPanel());
        }
        //--------------------------------------------------------------------------------
        // [SUBSECTION END] UI binding and core controls
        //--------------------------------------------------------------------------------

        mSshCard = findViewById(R.id.ssh_card);
        mSshInfoText = findViewById(R.id.ssh_info_text);

        // Update banner
        mUpdateBanner = findViewById(R.id.update_banner);
        mUpdateBannerText = findViewById(R.id.update_banner_text);
        mUpdateBannerController = new DashboardUpdateBannerController(this, mUpdateBanner, mUpdateBannerText);

        // OpenClaw version label
        mOpenclawVersionText = findViewById(R.id.openclaw_version_text);
        mOpenclawCheckUpdateButton = findViewById(R.id.btn_check_openclaw_update);
        if (mOpenclawCheckUpdateButton != null) {
            mOpenclawCheckUpdateButton.setOnClickListener(v -> {
                if (mOpenclawUpdateController != null) {
                    mOpenclawUpdateController.forceCheckOpenclawUpdate();
                }
            });
        }
        mBackToAgentSelectionButton = findViewById(R.id.btn_back_to_agent_selection);
        if (mBackToAgentSelectionButton != null) {
            mBackToAgentSelectionButton.setOnClickListener(v -> openAgentSelection());
        }
        mOpenclawWebUiButton = findViewById(R.id.btn_open_openclaw_web_ui);
        if (mOpenclawWebUiButton != null) {
            mOpenclawWebUiButton.setOnClickListener(v -> openOpenclawWebUi());
        }
        mOpenclawLogButton = findViewById(R.id.btn_view_openclaw_log);
        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setOnClickListener(v -> showOpenclawLog());
        }
        mOpenclawBackupButton = findViewById(R.id.btn_backup_openclaw_config);
        if (mOpenclawBackupButton != null) {
            mOpenclawBackupButton.setOnClickListener(v -> {
                if (mOpenclawConfigController != null) {
                    mOpenclawConfigController.backupOpenclawConfigToSdcard();
                }
            });
        }
        mOpenclawRestoreButton = findViewById(R.id.btn_restore_openclaw_config);
        if (mOpenclawRestoreButton != null) {
            mOpenclawRestoreButton.setOnClickListener(v -> {
                if (mOpenclawConfigController != null) {
                    mOpenclawConfigController.restoreOpenclawConfigFromSdcard();
                }
            });
        }
        mOpenclawUpdateController = new OpenclawUpdateController(
            this,
            mHandler,
            mOpenclawVersionText,
            mOpenclawCheckUpdateButton,
            mStartButton,
            mStopButton,
            mRestartButton,
            this::refreshStatus
        );
        mOpenclawLogController = new OpenclawLogController(this, mHandler, mOpenclawLogButton);
        mOpenclawConfigController = new OpenclawConfigController(
            this,
            mHandler,
            mOpenclawBackupButton,
            mOpenclawRestoreButton,
            this::notifyRuntimeModeSectionActivated
        );
        mOpenclawWebUiController = new OpenclawWebUiController(this, mHandler, mOpenclawWebUiButton);
        if (mRuntimeModeTabs != null) {
            setRuntimeMode(RuntimeMode.GATEWAY);
        }
        // Load SSH info
        if (mSshCard != null && mSshInfoText != null) {
            mNetworkInfoController = new DashboardNetworkInfoController(this, mSshCard, mSshInfoText);
            mNetworkInfoController.refreshSshInfo();
        }

        //--------------------------------------------------------------------------------
        // [SUBSECTION] OpenClaw feature wiring
        //--------------------------------------------------------------------------------
        // Bind to service
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Check for app updates (also picks up results from launcher check)
        UpdateChecker.check(this, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String downloadUrl, String notes) {
                if (mUpdateBannerController != null) {
                    mUpdateBannerController.show(latestVersion, downloadUrl);
                }
            }

            @Override
            public void onNoUpdate() {
                if (mUpdateBannerController != null) {
                    mUpdateBannerController.hide();
                }
            }
        });

        // Also check stored result in case launcher already fetched it
        String[] stored = UpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            if (mUpdateBannerController != null) {
                mUpdateBannerController.show(stored[0], stored[1]);
            }
        }
        registerBackInvokedCallback();
        //--------------------------------------------------------------------------------
        // [SUBSECTION END] OpenClaw feature wiring
        //--------------------------------------------------------------------------------

        //================================================================================
        // [SUBSECTION END] Dashboard initialization
        //================================================================================
    }

    private String getRuntimeModeSectionLabel(@Nullable DashboardRuntimeModeSection section) {
        return section == null ? "null" : section.getClass().getSimpleName();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Cancel all pending callbacks to prevent memory leak
        mHandler.removeCallbacksAndMessages(null);
        mStatusRefreshRunnable = null;

        if (mOpenclawUpdateController != null) {
            mOpenclawUpdateController.onDestroy();
        }
        if (mOpenclawConfigController != null) {
            mOpenclawConfigController.onDestroy();
        }
        if (mOpenclawWebUiController != null) {
            mOpenclawWebUiController.onDestroy();
        }
        
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
        stopStatusRefresh();
        mHandler.removeCallbacksAndMessages(null);
        applyRuntimeModeDependentWebUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUiVisible = true;
        applyRuntimeModeDependentWebUiState();
        if (mCurrentRuntimeModeSection == null) {
            setRuntimeModeSection(mRuntimeMode);
        }
        Logger.logDebug(LOG_TAG, "onResume -> section=" + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection)
            + ", runtimeMode=" + mRuntimeMode
            + ", bound=" + mBound);
        bindRuntimeScopedOpenclawViews();
        notifyRuntimeModeSectionActivated();
        if (mBound) {
            startStatusRefresh();
            refreshStatus();
            notifyRuntimeModeSectionServiceConnected();
        }
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
        if (requestCode == 3001) {
            if (mOpenclawConfigController != null) {
                mOpenclawConfigController.onRequestPermissionsResult();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 3001) {
            if (mOpenclawConfigController != null) {
                mOpenclawConfigController.onActivityResult();
            }
        }
    }
    //========================================================================================
    // [SECTION END] Lifecycle methods
    //========================================================================================

    //========================================================================================
    // [SECTION] Core runtime orchestration
    //========================================================================================

    private void stopStatusRefresh() {
        if (mStatusRefreshRunnable != null) {
            mHandler.removeCallbacks(mStatusRefreshRunnable);
            mStatusRefreshRunnable = null;
        }
    }
    //========================================================================================
    // [SECTION END] Core runtime orchestration
    //========================================================================================

    //========================================================================================
    // [SECTION] Runtime status and controls
    //========================================================================================

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
    private void startGatewayMonitorService(@Nullable RuntimeMode runtimeMode) {
        Intent serviceIntent = new Intent(this, GatewayMonitorService.class);
        serviceIntent.putExtra(
            GatewayMonitorService.EXTRA_RUNTIME_MODE,
            runtimeMode == RuntimeMode.NODE ? "node" : "gateway"
        );
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
     * Refresh runtime status and uptime
     */
    private void refreshStatus() {
        if (!mUiVisible) {
            return;
        }
        if (!mBound || mBotDropService == null || mCurrentRuntimeModeSection == null) {
            updateRuntimeStatus(false, null);
            return;
        }

        mCurrentRuntimeModeSection.queryRuntimeStatus(mBotDropService, (isRunning, uptimeText) -> {
            if (!mUiVisible) {
                return;
            }
            if (mCurrentRuntimeModeSection == null) {
                return;
            }

            updateRuntimeStatus(isRunning, uptimeText);
            if (mCurrentRuntimeModeSection.supportsGatewayErrorMonitoring()) {
                if (isRunning) {
                    checkCurrentRuntimeErrors();
                    return;
                }
                mCurrentRuntimeModeSection.onRuntimeErrorChanged(null);
            } else {
                mCurrentRuntimeModeSection.onRuntimeErrorChanged(null);
            }
        });
    }

    /**
     * Update the status UI based on runtime state
     */
    private void updateRuntimeStatus(boolean isRunning, @Nullable String uptimeText) {
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onRuntimeStatusUpdated(isRunning, uptimeText);
        if (isRunning) {
            setButtonState(mStartButton, false, true);
            setButtonState(mStopButton, true, false);
            setButtonState(mRestartButton, true, true);
        } else {
            setButtonState(mStartButton, true, true);
            setButtonState(mStopButton, false, false);
            setButtonState(mRestartButton, false, true);
        }
    }

    private void setButtonState(Button button, boolean enabled, boolean isFilled) {
        button.setEnabled(enabled);
        if (enabled) {
            button.setAlpha(1.0f);
            button.setTextColor(isFilled ? ContextCompat.getColor(this, R.color.botdrop_background) : ContextCompat.getColor(this, R.color.botdrop_accent));
        } else {
            button.setAlpha(0.5f);
            button.setTextColor(ContextCompat.getColor(this, R.color.botdrop_secondary_text));
        }
    }
    //========================================================================================
    // [SECTION END] Runtime status and controls
    //========================================================================================

    //========================================================================================
    // [SECTION] Runtime mode switching
    //========================================================================================

    private void setRuntimeMode(RuntimeMode runtimeMode) {
        Logger.logDebug(LOG_TAG, "setRuntimeMode invoked -> target=" + runtimeMode
            + ", current=" + mRuntimeMode
            + ", currentSection=" + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection));
        if (runtimeMode == null) {
            return;
        }
        if (mRuntimeMode == runtimeMode) {
            updateRuntimeModeTabsSelection();
            if (mCurrentRuntimeModeSection == null) {
                setRuntimeModeSection(runtimeMode);
            }
            if (mCurrentRuntimeModeSection != null) {
                updateRuntimeStatus(false, null);
            }
            applyRuntimeModeDependentWebUiState();
            notifyRuntimeModeSectionActivated();
            return;
        }

        DashboardRuntimeModeSection currentSection = mCurrentRuntimeModeSection;
        if (currentSection != null) {
            currentSection.onRuntimeModeDeactivated();
        }

        mRuntimeMode = runtimeMode;
        mLastErrorCheckAtMs = 0;
        clearCurrentRuntimeError();
        startGatewayMonitorService(runtimeMode);
        setRuntimeModeSection(runtimeMode);
        applyRuntimeModeDependentWebUiState();
        notifyRuntimeModeSectionActivated();

        updateRuntimeModeTabsSelection();
        updateRuntimeStatus(false, null);
        if (mBound && mBotDropService != null) {
            refreshStatus();
            notifyRuntimeModeSectionServiceConnected();
        }
    }

    @Nullable
    BotDropService getCurrentBotDropService() {
        if (!mBound || mBotDropService == null) {
            return null;
        }
        return mBotDropService;
    }

    boolean isBoundToBotDropService() {
        return mBound && mBotDropService != null;
    }

    private void setRuntimeModeSection(RuntimeMode runtimeMode) {
        Logger.logDebug(LOG_TAG, "setRuntimeModeSection -> target=" + runtimeMode
            + ", currentSection=" + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection));
        DashboardRuntimeModeSection targetSection;
        if (runtimeMode == RuntimeMode.GATEWAY) {
            targetSection = mGatewayRuntimeModeSection;
        } else {
            targetSection = mNodeRuntimeModeSection;
        }
        if (targetSection == mCurrentRuntimeModeSection) {
            Logger.logDebug(LOG_TAG, "setRuntimeModeSection skip: same section as current");
            return;
        }

        Fragment existingSection = getSupportFragmentManager().findFragmentByTag(RUNTIME_MODE_FRAGMENT_TAG);
        if (existingSection instanceof DashboardRuntimeModeSection
            && targetSection.getClass() == existingSection.getClass()) {
            mCurrentRuntimeModeSection = (DashboardRuntimeModeSection) existingSection;
            Logger.logDebug(LOG_TAG, "setRuntimeModeSection reuse existing fragment instance="
                + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection)
                + ", viewAttached=" + (((Fragment) mCurrentRuntimeModeSection).getView() != null));
            bindRuntimeScopedOpenclawViews();
            return;
        }

        mCurrentRuntimeModeSection = targetSection;
        Logger.logDebug(LOG_TAG, "setRuntimeModeSection commit runtime fragment " + getRuntimeModeSectionLabel(targetSection));
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.runtime_mode_fragment_container, (Fragment) targetSection, RUNTIME_MODE_FRAGMENT_TAG)
            .commitNow();
        bindRuntimeScopedOpenclawViews();
    }

    private void bindRuntimeScopedOpenclawViews() {
        String sectionLabel = getRuntimeModeSectionLabel(mCurrentRuntimeModeSection);
        Logger.logDebug(LOG_TAG, "bindRuntimeScopedOpenclawViews begin section=" + sectionLabel);
        View runtimeSectionRoot = null;
        if (mCurrentRuntimeModeSection instanceof Fragment) {
            runtimeSectionRoot = ((Fragment) mCurrentRuntimeModeSection).getView();
        }

        Logger.logDebug(LOG_TAG, "bindRuntimeScopedOpenclawViews root=" + (runtimeSectionRoot == null ? "null" : "attached")
            + ", viewType=" + ((runtimeSectionRoot == null || runtimeSectionRoot.getClass() == null)
            ? "n/a"
            : runtimeSectionRoot.getClass().getSimpleName()));

        if (runtimeSectionRoot != null) {
            mOpenclawVersionText = runtimeSectionRoot.findViewById(R.id.openclaw_version_text);
            mOpenclawWebUiButton = runtimeSectionRoot.findViewById(R.id.btn_open_openclaw_web_ui);
            mOpenclawLogButton = runtimeSectionRoot.findViewById(R.id.btn_view_openclaw_log);
        } else {
            mOpenclawVersionText = findViewById(R.id.openclaw_version_text);
            mOpenclawWebUiButton = findViewById(R.id.btn_open_openclaw_web_ui);
            mOpenclawLogButton = findViewById(R.id.btn_view_openclaw_log);
        }

        if (mOpenclawWebUiButton != null) {
            mOpenclawWebUiButton.setOnClickListener(v -> openOpenclawWebUi());
        }
        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setOnClickListener(v -> showOpenclawLog());
        }
        if (mOpenclawUpdateController != null) {
            mOpenclawUpdateController.setOpenclawVersionText(mOpenclawVersionText);
        }
        if (mOpenclawWebUiController != null) {
            mOpenclawWebUiController.setOpenclawWebUiButton(mOpenclawWebUiButton);
        }
        if (mOpenclawLogController != null) {
            mOpenclawLogController.setOpenclawLogButton(mOpenclawLogButton);
        }

        Logger.logDebug(LOG_TAG, "bindRuntimeScopedOpenclawViews end -> version=" + (mOpenclawVersionText != null)
            + ", webUi=" + (mOpenclawWebUiButton != null)
            + ", webUiEnabled=" + (mOpenclawWebUiButton != null && mOpenclawWebUiButton.isEnabled())
            + ", webUiVisible=" + (mOpenclawWebUiButton != null && mOpenclawWebUiButton.getVisibility() == View.VISIBLE)
            + ", log=" + (mOpenclawLogButton != null));
    }

    private void notifyRuntimeModeSectionActivated() {
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onRuntimeModeActivated();
    }

    private void notifyRuntimeModeSectionServiceConnected() {
        if (mCurrentRuntimeModeSection == null || !mBound || mBotDropService == null) {
            return;
        }
        mCurrentRuntimeModeSection.onServiceConnected(mBotDropService);
    }

    private void notifyRuntimeModeSectionServiceDisconnected() {
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onServiceDisconnected();
    }

    private void updateRuntimeModeTabsSelection() {
        if (mRuntimeModeTabs == null) {
            return;
        }
        int target = mRuntimeMode == RuntimeMode.NODE ? 1 : 0;
        if (mRuntimeModeTabs.getSelectedTabPosition() == target) {
            return;
        }
        TabLayout.Tab targetTab = mRuntimeModeTabs.getTabAt(target);
        if (targetTab != null) {
            targetTab.select();
        }
    }

    private void startCurrentRuntime() {
        if (!mBound || mBotDropService == null) {
            return;
        }
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        startCurrentRuntimeWithMutualExclusion();
    }

    private void startCurrentRuntimeWithMutualExclusion() {
        if (mCurrentRuntimeModeSection == null || mBotDropService == null) {
            return;
        }

        RuntimeMode runtimeToStopBeforeStart = getMutuallyExclusiveRuntime();
        if (runtimeToStopBeforeStart == null) {
            startCurrentRuntimeInternal();
            return;
        }

        mStartButton.setEnabled(false);
        isRuntimeRunning(runtimeToStopBeforeStart, isRunning -> {
            if (!isRunning) {
                startCurrentRuntimeInternal();
                return;
            }

            showRuntimeSwitchConfirmDialog(
                runtimeToStopBeforeStart,
                this::stopConflictingRuntimeAndStartTarget
            );
        });
    }

    private void startCurrentRuntimeInternal() {
        if (!mBound || mBotDropService == null || mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onStartRuntime(
            this,
            mBotDropService,
            mStartButton,
            this::refreshStatus
        );
    }

    private void stopCurrentRuntime() {
        if (!mBound || mBotDropService == null) {
            return;
        }
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onStopRuntime(
            this,
            mBotDropService,
            mStopButton,
            this::refreshStatus
        );
    }

    /**
     * Restart the current runtime (for control button)
     */
    private void restartCurrentRuntimeForControl() {
        if (!mBound || mBotDropService == null) {
            return;
        }
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onRestartRuntime(
            this,
            mBotDropService,
            mRestartButton,
            this::refreshStatus
        );
    }

    void restartCurrentRuntimeAfterModelChange() {
        if (!mBound || mBotDropService == null) {
            return;
        }
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onRestartAfterModelChange(this, mBotDropService, this::refreshStatus);
    }

    private RuntimeMode getMutuallyExclusiveRuntime() {
        if (mRuntimeMode == null) {
            return null;
        }
        return mRuntimeMode == RuntimeMode.GATEWAY ? RuntimeMode.NODE : RuntimeMode.GATEWAY;
    }

    private String getRuntimeModeDisplayName(@NonNull RuntimeMode runtimeMode) {
        if (runtimeMode == RuntimeMode.GATEWAY) {
            return getString(R.string.botdrop_runtime_mode_gateway);
        }
        return getString(R.string.botdrop_runtime_mode_node);
    }

    private void stopConflictingRuntimeAndStartTarget() {
        RuntimeMode conflictingMode = getMutuallyExclusiveRuntime();
        if (conflictingMode == null || mBotDropService == null) {
            startCurrentRuntimeInternal();
            return;
        }

        stopRuntime(conflictingMode, this::startCurrentRuntimeInternal);
    }

    private void stopRuntime(@NonNull RuntimeMode runtimeMode, @NonNull Runnable onSuccess) {
        if (mBotDropService == null) {
            onSuccess.run();
            return;
        }

        if (runtimeMode == RuntimeMode.GATEWAY) {
            mBotDropService.stopGateway(result -> {
                if (!isCommandSuccess(result)) {
                    Toast.makeText(
                        this,
                        getString(R.string.botdrop_gateway_stop_failed),
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshStatus();
                    return;
                }
                mHandler.postDelayed(onSuccess, 1000);
            });
            return;
        }

        mBotDropService.stopNode(result -> {
            if (!isCommandSuccess(result)) {
                Toast.makeText(
                    this,
                    getString(R.string.botdrop_node_stop_failed),
                    Toast.LENGTH_SHORT
                ).show();
                refreshStatus();
                return;
            }
            mHandler.postDelayed(onSuccess, 1000);
        });
    }

    private void isRuntimeRunning(@NonNull RuntimeMode runtimeMode, @NonNull RuntimeRunningCallback callback) {
        if (mBotDropService == null) {
            callback.onResult(false);
            return;
        }

        if (runtimeMode == RuntimeMode.GATEWAY) {
            mBotDropService.isGatewayRunning(result -> callback.onResult(
                result != null && result.success && "running".equals(result.stdout.trim())
            ));
            return;
        }

        mBotDropService.isNodeRunning(result -> callback.onResult(
            result != null && result.success && "running".equals(result.stdout.trim())
        ));
    }

    private boolean isCommandSuccess(@Nullable BotDropService.CommandResult result) {
        return result != null && result.success;
    }

    private void showRuntimeSwitchConfirmDialog(@NonNull RuntimeMode runningMode, @NonNull Runnable onConfirm) {
        if (mCurrentRuntimeModeSection == null) {
            return;
        }

        RuntimeMode targetMode = mRuntimeMode == null ? RuntimeMode.GATEWAY : mRuntimeMode;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.botdrop_runtime_mode_switch_title))
            .setMessage(getString(
                R.string.botdrop_runtime_mode_switch_message,
                getRuntimeModeDisplayName(runningMode),
                getRuntimeModeDisplayName(targetMode)
            ))
            .setNegativeButton(getString(R.string.botdrop_cancel), (d, w) -> {
                refreshStatus();
            })
            .setPositiveButton(getString(R.string.botdrop_confirm), (d, w) -> {
                onConfirm.run();
            })
            .setCancelable(true)
            .create();
        dialog.show();
    }

    private interface RuntimeRunningCallback {
        void onResult(boolean isRunning);
    }
    //========================================================================================
    // [SECTION END] Runtime mode switching
    //========================================================================================

    //========================================================================================
    // [SECTION] Navigation and host actions
    //========================================================================================

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
    //========================================================================================
    // [SECTION END] Navigation and host actions
    //========================================================================================

    //========================================================================================
    // [SECTION] OpenClaw integration
    //========================================================================================

    private void openOpenclawWebUi() {
        Logger.logDebug(LOG_TAG, "openOpenclawWebUi click -> section=" + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection)
            + ", button=" + (mOpenclawWebUiButton == null ? "null" : mOpenclawWebUiButton.getText())
            + ", uiVisible=" + mUiVisible
            + ", bound=" + mBound
            + ", service=" + (mBotDropService == null ? "null" : "ready"));
        if (mOpenclawWebUiController != null) {
            mOpenclawWebUiController.openOpenclawWebUi(
                mCurrentRuntimeModeSection,
                mUiVisible,
                mBound,
                mBotDropService
            );
        }
    }

    private void applyRuntimeModeDependentWebUiState() {
        if (mOpenclawWebUiController != null) {
            mOpenclawWebUiController.applyRuntimeModeDependentState(mCurrentRuntimeModeSection);
            return;
        }

        if (mOpenclawWebUiButton == null) {
            return;
        }

        if (mCurrentRuntimeModeSection == null || !mCurrentRuntimeModeSection.isOpenclawWebUiEnabled()) {
            mOpenclawWebUiButton.setVisibility(View.GONE);
            mOpenclawWebUiButton.setEnabled(false);
            mOpenclawWebUiButton.setText(getString(R.string.botdrop_open_web_ui_not_available_in_node_mode));
        } else {
            mOpenclawWebUiButton.setVisibility(View.VISIBLE);
            mOpenclawWebUiButton.setEnabled(true);
            mOpenclawWebUiButton.setAlpha(1f);
            mOpenclawWebUiButton.setText(getString(R.string.botdrop_open_web_ui));
        }
    }

    void showOpenclawLog() {
        Logger.logDebug(LOG_TAG, "showOpenclawLog click -> section=" + getRuntimeModeSectionLabel(mCurrentRuntimeModeSection)
            + ", button=" + (mOpenclawLogButton == null ? "null" : "present")
            + ", service=" + (mBotDropService == null ? "null" : "ready"));
        if (mOpenclawLogController != null) {
            mOpenclawLogController.showOpenclawLog(mRuntimeMode == RuntimeMode.NODE);
        }
    }
    //========================================================================================
    // [SECTION END] OpenClaw integration
    //========================================================================================

    //========================================================================================
    // [SECTION] Gateway diagnostics
    //========================================================================================

    private void checkCurrentRuntimeErrors() {
        if (mCurrentRuntimeModeSection == null
            || !mCurrentRuntimeModeSection.supportsGatewayErrorMonitoring()
            || !mBound
            || mBotDropService == null
            ) {
            clearCurrentRuntimeError();
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
                    clearCurrentRuntimeError();
                    return;
                }
                String errorLine = extractRecentGatewayError(result.stdout);
                if (mCurrentRuntimeModeSection != null) {
                    mCurrentRuntimeModeSection.onRuntimeErrorChanged(errorLine);
                }
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

    private void clearCurrentRuntimeError() {
        if (mCurrentRuntimeModeSection == null) {
            return;
        }
        mCurrentRuntimeModeSection.onRuntimeErrorChanged(null);
    }
    //========================================================================================
    // [SECTION END] Gateway diagnostics
    //========================================================================================
}
