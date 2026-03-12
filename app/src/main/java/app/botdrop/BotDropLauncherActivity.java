package app.botdrop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;


import java.io.File;

/**
 * Launcher activity with two phases:
 *
 * Phase 1 (Welcome): Guided permission requests — user taps buttons to grant
 * notification permission and battery optimization exemption, with clear explanations.
 *
 * Phase 2 (Loading): Routes to the appropriate screen based on installation state:
 * 1. If bootstrap not extracted -> Wait for TermuxInstaller
 * 2. If OpenClaw not installed/configured -> SetupActivity (agent -> install -> auth)
 * 3. If channel not configured -> SetupActivity (channel setup)
 * 4. All ready -> DashboardActivity
 */
public class BotDropLauncherActivity extends Activity {

    private static final String LOG_TAG = "BotDropLauncherActivity";
    private static final int REQUEST_CODE_NOTIFICATION_SETTINGS = 1001;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1002;
    private static final String PREFS_NAME = "botdrop_launcher";
    private static final String PREF_ONBOARDING_CONTINUE = "onboarding_continue_clicked";

    // Views
    private View mWelcomeContainer;
    private View mLoadingContainer;
    private TextView mStatusText;
    private Button mNotificationButton;
    private Button mBatteryButton;
    private Button mBackgroundSettingsButton;
    private Button mContinueButton;
    private Button mCheckUpdateButton;
    private TextView mNotificationStatus;
    private TextView mBatteryStatus;
    private TextView mBackgroundHintText;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPermissionsPhaseComplete = false;
    private boolean mContinueClickedPersisted = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_launcher);

        mWelcomeContainer = findViewById(R.id.welcome_container);
        mLoadingContainer = findViewById(R.id.loading_container);
        mStatusText = findViewById(R.id.launcher_status_text);
        mNotificationButton = findViewById(R.id.btn_notification_permission);
        mBatteryButton = findViewById(R.id.btn_battery_permission);
        mBackgroundSettingsButton = findViewById(R.id.btn_background_settings);
        mContinueButton = findViewById(R.id.btn_continue);
        mCheckUpdateButton = findViewById(R.id.btn_check_update);
        mNotificationStatus = findViewById(R.id.notification_status);
        mBatteryStatus = findViewById(R.id.battery_status);
        mBackgroundHintText = findViewById(R.id.background_hint_text);

        mContinueClickedPersisted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_ONBOARDING_CONTINUE, false);

        // Upgrade migration: clean deprecated keys from existing OpenClaw config.
        BotDropConfig.sanitizeLegacyConfig();

        // Trigger update check early (results stored for Dashboard to display)
        UpdateChecker.check(this, null);

        mNotificationButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_notification_tap");
            openNotificationSettings();
        });
        mBatteryButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_battery_tap");
            requestBatteryOptimization();
        });
        mBackgroundSettingsButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_background_tap");
            openAdvancedBackgroundSettings();
        });
        mCheckUpdateButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_update_check_tap");
            checkUpdateManually();
        });
        mContinueButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_continue_tap");
            mPermissionsPhaseComplete = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ONBOARDING_CONTINUE, true)
                .apply();
            mContinueClickedPersisted = true;
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mPermissionsPhaseComplete || mContinueClickedPersisted) {
            // User has explicitly continued before; proceed automatically.
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
            return;
        }

        // Show welcome screen and update permission status. Do not auto-advance:
        // the user must tap Continue to start bootstrap/setup work.
        showWelcomePhase();
        updatePermissionStatus();
        // Continue with cached permission status and setup flow.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // --- Phase management ---

    private void showWelcomePhase() {
        mWelcomeContainer.setVisibility(View.VISIBLE);
        mLoadingContainer.setVisibility(View.GONE);
        AnalyticsManager.logScreen(this, "launcher_welcome", "BotDropLauncherActivity");
    }

    private void showLoadingPhase() {
        mWelcomeContainer.setVisibility(View.GONE);
        mLoadingContainer.setVisibility(View.VISIBLE);
        AnalyticsManager.logScreen(this, "launcher_loading", "BotDropLauncherActivity");
    }

    // --- Permission checks ---

    private boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean isBatteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true; // Pre-Android M: no battery optimization
    }

    private int getRestrictBackgroundStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
            return cm.getRestrictBackgroundStatus();
        } catch (Exception ignored) {
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        }
    }

    // --- Permission requests ---

    /**
     * Open app notification settings page.
     * targetSdk=28 means requestPermissions(POST_NOTIFICATIONS) is a no-op on Android 13+.
     * Opening the settings page works reliably across all Android versions.
     */
    private void openNotificationSettings() {
        Logger.logInfo(LOG_TAG, "Opening notification settings");
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivityForResult(intent, REQUEST_CODE_NOTIFICATION_SETTINGS);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to open notification settings: " + e.getMessage());
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Logger.logInfo(LOG_TAG, "Requesting battery optimization exemption");
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to request battery optimization: " + e.getMessage());
            }
        }
    }

    /**
     * Open OEM/system pages that commonly control background throttling.
     * This cannot be fully automated across all OEMs, but we can take the user to the right place quickly.
     */
    private void openAdvancedBackgroundSettings() {
        // 1) If background data is restricted, take user straight to the Data Saver allowlist screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int status = getRestrictBackgroundStatus();
            if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
                try {
                    // ACTION_DATA_SAVER_SETTINGS is not present on some compile SDKs; use literal.
                    startActivity(new Intent("android.settings.DATA_SAVER_SETTINGS"));
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }

        // 2) Otherwise, open app details where most ROMs surface Battery: Unrestricted.
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to open app details settings: " + e.getMessage());
        }

        // 3) Last resort: general settings.
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception ignored) {}
    }

    // --- Permission results ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_NOTIFICATION_SETTINGS) {
            if (areNotificationsEnabled()) {
                Logger.logInfo(LOG_TAG, "Notifications enabled");
            } else {
                Logger.logWarn(LOG_TAG, "Notifications still disabled");
            }
            updatePermissionStatus();
        } else if (requestCode == REQUEST_CODE_BATTERY_OPTIMIZATION) {
            if (isBatteryOptimizationExempt()) {
                Logger.logInfo(LOG_TAG, "Battery optimization exemption granted");
            } else {
                Logger.logWarn(LOG_TAG, "Battery optimization exemption denied");
            }
            updatePermissionStatus();
        }
    }

    // --- UI updates ---

    private void updatePermissionStatus() {
        boolean notifGranted = areNotificationsEnabled();
        boolean batteryExempt = isBatteryOptimizationExempt();
        int backgroundStatus = getRestrictBackgroundStatus();

        // Notification status
        if (notifGranted) {
            mNotificationStatus.setText("✓");
            mNotificationStatus.setVisibility(View.VISIBLE);
            mNotificationButton.setEnabled(false);
            mNotificationButton.setText(R.string.botdrop_enabled);
        } else {
            mNotificationStatus.setVisibility(View.GONE);
            mNotificationButton.setEnabled(true);
            mNotificationButton.setText(R.string.botdrop_allow);
        }

        // Battery status
        if (batteryExempt) {
            mBatteryStatus.setText("✓");
            mBatteryStatus.setVisibility(View.VISIBLE);
            mBatteryButton.setEnabled(false);
            mBatteryButton.setText(R.string.botdrop_granted);
        } else {
            mBatteryStatus.setVisibility(View.GONE);
            mBatteryButton.setEnabled(true);
            mBatteryButton.setText(R.string.botdrop_allow);
        }

        // Background hint status (informational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_restricted);
            } else if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_allowed);
            } else {
                mBackgroundHintText.setText(R.string.botdrop_background_data_hint);
            }
        }

        // Enable continue when both handled
        mContinueButton.setEnabled(notifGranted && batteryExempt);
    }

    private void checkUpdateManually() {
        mCheckUpdateButton.setEnabled(false);
        mCheckUpdateButton.setText(R.string.botdrop_checking_updates);

        UpdateChecker.forceCheckWithFeedback(this, (updateAvailable, latestVersion, downloadUrl, notes, message) -> {
            mCheckUpdateButton.setEnabled(true);
            mCheckUpdateButton.setText(R.string.botdrop_check_update);
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            } else if (updateAvailable) {
                Toast.makeText(this, getString(R.string.botdrop_update_available_version, latestVersion), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.botdrop_no_update_available), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // --- Routing ---

    private void checkAndRoute() {
        // Check 1: Bootstrap installed?
        if (!BotDropService.isBootstrapInstalled()) {
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting for TermuxInstaller");
            mStatusText.setText(R.string.botdrop_setting_up_environment);

            TermuxInstaller.setupBootstrapIfNeeded(this, this::checkAndRoute);
            return;
        }

        // Check 2: OpenClaw installed?
        if (!BotDropService.isOpenclawInstalled()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not installed, routing to agent selection");
            mStatusText.setText(R.string.botdrop_setup_required);

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_AGENT_SELECT);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: OpenClaw configured (API key)?
        if (!BotDropService.isOpenclawConfigured()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not configured, routing to agent-first setup");
            mStatusText.setText(R.string.botdrop_setup_required);

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_AGENT_SELECT);
            startActivity(intent);
            finish();
            return;
        }

        // Check 4: Channel configured?
        if (!hasChannelConfigured()) {
            Logger.logInfo(LOG_TAG, "No channel configured, routing to channel setup");
            mStatusText.setText(R.string.botdrop_channel_setup_required);

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_CHANNEL);
            startActivity(intent);
            finish();
            return;
        }

        // All ready - go to DashboardActivity
        Logger.logInfo(LOG_TAG, "All ready, routing to dashboard");
        mStatusText.setText(R.string.botdrop_starting_status);

        Intent intent = new Intent(this, DashboardActivity.class);
        startActivity(intent);
        finish();
    }

    private boolean hasChannelConfigured() {
        return ChannelSetupHelper.hasAnyChannelConfigured();
    }
}
