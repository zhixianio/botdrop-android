package com.termux.shizuku;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.app.AnalyticsManager;
import com.termux.shared.logger.Logger;

import java.util.Locale;

import rikka.shizuku.Shizuku;

public class ShizukuStatusActivity extends AppCompatActivity {

    private static final String LOG_TAG = "ShizukuStatus";
    public static final String EXTRA_AUTO_START = "app.botdrop.extra.AUTO_START_SHIZUKU";
    public static final String EXTRA_AUTO_REQUEST_PERMISSION = "app.botdrop.extra.AUTO_REQUEST_SHIZUKU_PERMISSION";
    private static final int AUTO_REQUEST_MAX_RETRY = 16;
    private static final int AUTO_REQUEST_RETRY_DELAY_MS = 250;

    private TextView statusText;
    private Button permissionButton;
    private Button openSettingsButton;
    private Button startButton;
    private boolean autoStartRequested;
    private boolean autoRequestPermissionRequested;
    private boolean autoFlowStarted;
    private int autoRequestPermissionAttempts;
    private volatile boolean destroyed;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::postUpdateStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::postUpdateStatus;
    private final Shizuku.OnRequestPermissionResultListener requestPermissionListener = this::onPermissionResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        statusText = new TextView(this);
        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_shizuku_refresh_tap");
            updateStatus();
        });

        startButton = new Button(this);
        startButton.setText("Bootstrap Shizuku");
        startButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_shizuku_bootstrap_tap");
            startShizuku();
        });

        permissionButton = new Button(this);
        permissionButton.setText("Request Shizuku Permission");
        permissionButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_shizuku_permission_tap");
            requestPermission();
        });

        openSettingsButton = new Button(this);
        openSettingsButton.setText("Open App Permissions");
        openSettingsButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "automation_shizuku_settings_tap");
            openPermissionsSetting();
        });

        root.addView(statusText);
        root.addView(refreshButton);
        root.addView(startButton);
        root.addView(permissionButton);
        root.addView(openSettingsButton);

        setContentView(root);

        setTitle("Shizuku (Single App)");
        AnalyticsManager.logScreen(this, "automation_shizuku_status", "ShizukuStatusActivity");
        updateStatus();
        autoStartRequested = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_START, false);
        autoRequestPermissionRequested = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_REQUEST_PERMISSION, false);

        if (autoStartRequested || autoRequestPermissionRequested) {
            AnalyticsManager.logEvent(this, "automation_shizuku_auto_flow_start");
            startPairingFlow();
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener, uiHandler);
        Shizuku.addBinderDeadListener(binderDeadListener, uiHandler);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        Shizuku.removeRequestPermissionResultListener(requestPermissionListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        super.onDestroy();
    }

    private void updateStatus() {
        if (statusText == null) return;

        boolean hasBinder = Shizuku.pingBinder();
        StringBuilder text = new StringBuilder();
        text.append("Package: ").append(getPackageName()).append('\n');
        text.append("Binder: ").append(hasBinder ? "READY" : "NOT READY").append('\n');
        if (permissionButton != null) {
            permissionButton.setEnabled(hasBinder);
        }

        if (hasBinder) {
            text.append("Shizuku version: ").append(Shizuku.getVersion()).append('\n');
            text.append("Patch: ").append(Shizuku.getServerPatchVersion()).append('\n');
            int perm = PackageManager.PERMISSION_DENIED;
            try {
                perm = Shizuku.checkSelfPermission();
            } catch (Throwable tr) {
                Logger.logWarn(LOG_TAG, "checkSelfPermission failed: " + tr.getMessage());
            }
            text.append("Runtime API permission in pm: ")
                    .append(isRuntimePermissionGranted() ? "GRANTED" : "DENIED")
                    .append('\n');
            text.append("API permission: ")
                    .append(perm == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "NOT GRANTED")
                    .append('\n');
            text.append("SELinux context: ").append(Shizuku.getSELinuxContext()).append('\n')
                    .append("Server uid: ").append(Shizuku.getUid()).append('\n');
            permissionButton.setText(perm == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku Permission already granted"
                    : "Request Shizuku Permission");
        } else {
            text.append("Tip: open root mode or ensure Shizuku server is running\n");
            permissionButton.setText("Request Shizuku Permission");
        }

        int startMode = ShizukuBootstrap.getLastStartMode(this);
        long lastAttempt = ShizukuBootstrap.getLastStartAttempt(this);
        text.append("Bootstrap mode: ").append(ShizukuBootstrap.startModeToString(startMode)).append('\n');
        text.append("Last start attempt: ")
                .append(lastAttempt <= 0 ? "never" : String.valueOf(lastAttempt))
                .append('\n');
        text.append("Last start error: ").append(ShizukuBootstrap.getLastStartError(this)).append('\n');

        text.append("SharedUID: ").append(android.os.Process.myUid()).append('\n')
            .append("Process: ").append(android.os.Process.myPid()).append('\n');

        statusText.setText(text.toString());
        Logger.logDebug(LOG_TAG, String.format(Locale.US, "status updated: %s", text));
    }

    private void requestPermission() {
        if (!Shizuku.pingBinder()) {
            AnalyticsManager.logEvent(this, "automation_shizuku_permission_blocked", "reason", "binder_not_ready");
            Toast.makeText(this, "Shizuku binder is not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        int granted;
        try {
            granted = Shizuku.checkSelfPermission();
        } catch (Throwable tr) {
            granted = PackageManager.PERMISSION_DENIED;
        }
        if (granted == PackageManager.PERMISSION_GRANTED) {
            AnalyticsManager.logEvent(this, "automation_shizuku_permission_already_granted");
            Toast.makeText(this, "Shizuku permission already granted", Toast.LENGTH_SHORT).show();
            return;
        }

        permissionButton.setEnabled(false);
        Toast.makeText(this, "Requesting Shizuku permission...", Toast.LENGTH_SHORT).show();
        Shizuku.addRequestPermissionResultListener(requestPermissionListener);
            Shizuku.requestPermission(0);
    }

    private void startShizuku() {
        new Thread(() -> {
            try {
                ShizukuBootstrap.bootstrap(ShizukuStatusActivity.this);
            } catch (Throwable tr) {
                Logger.logWarn(LOG_TAG, "bootstrap failed: " + tr.getMessage());
            }
            postUpdateStatus();
        }).start();
    }

    private void startPairingFlow() {
        if (autoFlowStarted) return;
        autoFlowStarted = true;
        if (autoStartRequested) {
            startShizuku();
        }
        if (autoRequestPermissionRequested) {
            waitAndRequestPermission();
        }
    }

    private void waitAndRequestPermission() {
        if (destroyed) return;
        if (autoRequestPermissionAttempts >= AUTO_REQUEST_MAX_RETRY) {
            AnalyticsManager.logEvent(this, "automation_shizuku_auto_flow_failed", "reason", "binder_timeout");
            Toast.makeText(this, "Shizuku binder is not ready; please press Bootstrap or reopen", Toast.LENGTH_LONG).show();
            autoFlowStarted = false;
            return;
        }
        autoRequestPermissionAttempts++;

        if (!Shizuku.pingBinder()) {
            uiHandler.postDelayed(this::waitAndRequestPermission, AUTO_REQUEST_RETRY_DELAY_MS);
            return;
        }
        requestPermission();
        autoFlowStarted = false;
    }

    private void openPermissionsSetting() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable tr) {
            Toast.makeText(this, "Unable to open permission settings", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isRuntimePermissionGranted() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null || info.requestedPermissionsFlags == null) return false;
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                if (ShizukuBootstrap.PERMISSION_API.equals(info.requestedPermissions[i])) {
                    return checkSelfPermission(ShizukuBootstrap.PERMISSION_API) == PackageManager.PERMISSION_GRANTED;
                }
            }
            return false;
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "runtime permission check failed: " + tr.getMessage());
            return false;
        }
    }

    private void onPermissionResult(int requestCode, int grantResult) {
        if (destroyed) {
            return;
        }

        try {
            AnalyticsManager.logEvent(
                this,
                grantResult == PackageManager.PERMISSION_GRANTED
                    ? "automation_shizuku_permission_granted"
                    : "automation_shizuku_permission_denied"
            );
            Toast.makeText(this,
                    grantResult == PackageManager.PERMISSION_GRANTED ? "Permission granted" : "Permission denied",
                    Toast.LENGTH_SHORT).show();
            postUpdateStatus();
        } finally {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener);
            permissionButton.setEnabled(Shizuku.pingBinder());
        }
    }

    private void postUpdateStatus() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateStatus();
        } else {
            uiHandler.post(this::updateStatus);
        }
    }

    private int dp(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
