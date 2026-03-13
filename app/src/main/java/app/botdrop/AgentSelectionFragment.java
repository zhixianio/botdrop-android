package app.botdrop;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Step 1 of setup: Choose which agent to install.
 *
 * Currently offers:
 * - OpenClaw (available, triggers install)
 * - OwliaBot (a distinct AI agent product, not a rename leftover - coming soon, disabled)
 */
public class AgentSelectionFragment extends Fragment {

    private static final String LOG_TAG = "AgentSelectionFragment";

    public static final String PREFS_NAME = "botdrop_settings";
    public static final String KEY_OPENCLAW_VERSION = "openclaw_install_version";
    private static final String PINNED_VERSION = "openclaw@2026.2.6";
    private static final int TAP_COUNT_THRESHOLD = 10;
    private static final long TAP_WINDOW_MS = 5000;
    private static final long OPENCLAW_VERSION_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);
    private static final int OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS = 180;
    private static final int OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS_RETRY = 300;
    private static final String KEY_OPENCLAW_VERSION_CACHE = "openclaw_versions_cache";
    private static final String KEY_OPENCLAW_VERSION_CACHE_TIME = "openclaw_versions_cache_time";

    private BotDropService mBotDropService;
    private boolean mServiceBound = false;
    private AlertDialog mOpenclawVersionManagerDialog;
    private StepProgressDialog mProgressDialog;
    private boolean mOpenclawVersionActionInProgress;
    private long mOpenclawVersionRequestId;
    private int mTapCount = 0;
    private long mFirstTapTime = 0;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mBotDropService = binder.getService();
            mServiceBound = true;
            Logger.logDebug(LOG_TAG, "BotDropService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
            mBotDropService = null;
            Logger.logDebug(LOG_TAG, "BotDropService disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_botdrop_agent_select, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Button installButton = view.findViewById(R.id.agent_openclaw_install);
        View versionManagerButton = view.findViewById(R.id.agent_openclaw_version_manager);
        final boolean isOpenclawInstalled = BotDropService.isOpenclawInstalled();
        installButton.setText(isOpenclawInstalled ? R.string.botdrop_open : R.string.botdrop_install);
        installButton.setOnClickListener(v -> {
            if (isOpenclawInstalled) {
                AnalyticsManager.logEvent(requireContext(), "agent_open_dashboard_tap");
                Logger.logInfo(LOG_TAG, "OpenClaw already installed, opening dashboard");
                openDashboard();
            } else {
                AnalyticsManager.logEvent(requireContext(), "agent_install_tap");
                Logger.logInfo(LOG_TAG, "OpenClaw selected for installation");
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.goToNextStep();
                }
            }
        });

        versionManagerButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(requireContext(), "agent_version_manager_tap");
            showOpenclawVersionListDialog();
        });

        // URL click handlers
        view.findViewById(R.id.agent_openclaw_url).setOnClickListener(v -> {
            AnalyticsManager.logEvent(requireContext(), "agent_openclaw_link_tap");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openclaw.ai")));
        });

        // Easter egg: tap OpenClaw icon 10 times to pin install version
        view.findViewById(R.id.agent_openclaw_icon).setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (mTapCount == 0 || now - mFirstTapTime > TAP_WINDOW_MS) {
                mTapCount = 1;
                mFirstTapTime = now;
            } else {
                mTapCount++;
            }

            if (mTapCount >= TAP_COUNT_THRESHOLD) {
                mTapCount = 0;
                showVersionPinDialog();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() == null) {
            return;
        }
        Intent intent = new Intent(getActivity(), BotDropService.class);
        boolean bound = getActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (bound) {
            mServiceBound = true;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        dismissOpenclawVersionManagerDialog();
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        }
        if (mServiceBound && getActivity() != null) {
            try {
                getActivity().unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
                // Service was not bound or already unbound.
            }
            mServiceBound = false;
            mBotDropService = null;
        }
    }

    private void showOpenclawVersionListDialog() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }

        if (!mServiceBound || mBotDropService == null) {
            BotDropDialogStyler.createBuilder(ctx)
                .setTitle(R.string.botdrop_openclaw_versions)
                .setMessage(R.string.botdrop_service_not_connected_try_later)
                .setNegativeButton(R.string.botdrop_close, null)
                .show();
            return;
        }

        if (mOpenclawVersionActionInProgress) {
            return;
        }

        mOpenclawVersionActionInProgress = true;
        final long requestId = ++mOpenclawVersionRequestId;
        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(ctx)
            .setTitle(R.string.botdrop_openclaw_versions)
            .setMessage(R.string.botdrop_loading_versions)
            .setCancelable(false)
            .setNegativeButton(R.string.botdrop_cancel, (d, w) -> {
                mOpenclawVersionActionInProgress = false;
                if (requestId == mOpenclawVersionRequestId) {
                    ++mOpenclawVersionRequestId;
                }
            })
            .create();
        mOpenclawVersionManagerDialog.show();

        fetchOpenclawVersions((versions, errorMessage) -> {
            if (requestId != mOpenclawVersionRequestId || getActivity() == null || !isAdded()) {
                mOpenclawVersionActionInProgress = false;
                return;
            }
            dismissOpenclawVersionManagerDialog();
            if (versions == null || versions.isEmpty()) {
                showOpenclawVersionManagerError(TextUtils.isEmpty(errorMessage)
                    ? getString(R.string.botdrop_no_versions_available)
                    : errorMessage);
                return;
            }
            showOpenclawVersions(versions);
        });
    }

    private void dismissOpenclawVersionManagerDialog() {
        if (mOpenclawVersionManagerDialog != null) {
            mOpenclawVersionManagerDialog.dismiss();
            mOpenclawVersionManagerDialog = null;
        }
        mOpenclawVersionActionInProgress = false;
    }

    private void showOpenclawVersionManagerError(String message) {
        Context ctx = getContext();
        if (ctx == null) {
            mOpenclawVersionActionInProgress = false;
            return;
        }
        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(ctx)
            .setTitle(R.string.botdrop_openclaw_versions)
            .setMessage(message)
            .setNegativeButton(R.string.botdrop_close, (d, w) -> mOpenclawVersionActionInProgress = false)
            .setPositiveButton(R.string.botdrop_retry, (d, w) -> showOpenclawVersionListDialog())
            .setCancelable(false)
            .setOnDismissListener(d -> mOpenclawVersionActionInProgress = false)
            .show();
    }

    private void showOpenclawVersions(List<String> versions) {
        Context ctx = getContext();
        if (ctx == null) {
            mOpenclawVersionActionInProgress = false;
            return;
        }

        List<String> normalized = OpenclawVersionUtils.normalizeVersionList(versions);
        if (normalized.isEmpty()) {
            mOpenclawVersionActionInProgress = false;
            showOpenclawVersionManagerError(getString(R.string.botdrop_no_valid_versions_found));
            return;
        }

        String currentVersion = OpenclawVersionUtils.normalizeForSort(BotDropService.getOpenclawVersion());

        // Ensure current version is in the list
        if (!TextUtils.isEmpty(currentVersion) && !normalized.contains(currentVersion)) {
            normalized.add(currentVersion);
            normalized = new ArrayList<>(OpenclawVersionUtils.sortAndLimit(normalized));
        }
        final List<String> finalNormalized = normalized;

        String[] labels = new String[normalized.size()];
        for (int i = 0; i < normalized.size(); i++) {
            String v = normalized.get(i);
            if (!TextUtils.isEmpty(currentVersion) && TextUtils.equals(currentVersion, v)) {
                labels[i] = getString(R.string.botdrop_openclaw_current_version, v);
            } else {
                labels[i] = getString(R.string.botdrop_openclaw_version, v);
            }
        }

        mOpenclawVersionActionInProgress = true;
        mOpenclawVersionManagerDialog = BotDropDialogStyler.createBuilder(ctx)
            .setTitle(R.string.botdrop_openclaw_versions)
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= finalNormalized.size()) {
                    mOpenclawVersionActionInProgress = false;
                    return;
                }
                handleOpenclawVersionPick(finalNormalized.get(which));
            })
            .setNegativeButton(R.string.botdrop_close, (d, w) -> mOpenclawVersionActionInProgress = false)
            .create();
        mOpenclawVersionManagerDialog.show();
    }

    private void handleOpenclawVersionPick(String version) {
        String picked = OpenclawVersionUtils.normalizeForSort(version);
        if (TextUtils.isEmpty(picked)) {
            Context ctx = getContext();
        if (ctx != null) {
                Toast.makeText(ctx, getString(R.string.botdrop_invalid_version_format), Toast.LENGTH_SHORT).show();
            }
            mOpenclawVersionActionInProgress = false;
            return;
        }

        String currentVersion = OpenclawVersionUtils.normalizeForSort(BotDropService.getOpenclawVersion());
        if (!TextUtils.isEmpty(currentVersion) && TextUtils.equals(currentVersion, picked)) {
            openDashboard();
            mOpenclawVersionActionInProgress = false;
            return;
        }

        final String installVersion = OpenclawVersionUtils.normalizeInstallVersion(picked);
        if (TextUtils.isEmpty(installVersion)) {
            Context ctx = getContext();
            if (ctx != null) {
                Toast.makeText(ctx, getString(R.string.botdrop_invalid_install_version), Toast.LENGTH_SHORT).show();
            }
            mOpenclawVersionActionInProgress = false;
            return;
        }

        Context ctx = getContext();
        if (ctx == null) {
            mOpenclawVersionActionInProgress = false;
            return;
        }

        if (BotDropService.isOpenclawInstalled()) {
            BotDropDialogStyler.createBuilder(ctx)
                .setTitle(R.string.botdrop_install)
                .setMessage(getString(
                    R.string.botdrop_install_openclaw_installed_title,
                    TextUtils.isEmpty(currentVersion) ? getString(R.string.botdrop_unknown) : currentVersion,
                    installVersion
                ))
                .setNegativeButton(R.string.botdrop_cancel, (d, w) -> mOpenclawVersionActionInProgress = false)
                .setPositiveButton(R.string.botdrop_install, (d, w) -> installOpenclawInPlace(installVersion))
                .setCancelable(false)
                .setOnDismissListener(d -> mOpenclawVersionActionInProgress = false)
                .show();
        } else {
            BotDropDialogStyler.createBuilder(ctx)
                .setTitle(R.string.botdrop_install)
                .setMessage(getString(R.string.botdrop_install_openclaw_not_installed_title, installVersion))
                .setNegativeButton(R.string.botdrop_cancel, (d, w) -> mOpenclawVersionActionInProgress = false)
                .setPositiveButton(R.string.botdrop_install, (d, w) -> installOpenclawWithSetup(installVersion))
                .setCancelable(false)
                .setOnDismissListener(d -> mOpenclawVersionActionInProgress = false)
                .show();
        }
    }

    private void installOpenclawInPlace(String installVersion) {
        if (mBotDropService == null) {
            mOpenclawVersionActionInProgress = false;
            return;
        }

        Context ctx = getContext();
        if (ctx == null) {
            mOpenclawVersionActionInProgress = false;
            return;
        }

        mProgressDialog = StepProgressDialog.create(
            ctx,
            R.string.botdrop_install,
            Arrays.asList(
                getString(R.string.botdrop_stopping_gateway),
                getString(R.string.botdrop_installing_update),
                getString(R.string.botdrop_finalizing),
                getString(R.string.botdrop_starting_gateway),
                getString(R.string.botdrop_refreshing_model_list)
            ),
            getString(R.string.botdrop_may_take_a_few_minutes)
        );
        mProgressDialog.show();
        AnalyticsManager.logEvent(ctx, "agent_version_install_started");

        mBotDropService.updateOpenclaw(installVersion, new BotDropService.UpdateProgressCallback() {
            @Override
            public void onStepStart(String message) {
                if (mProgressDialog == null) {
                    return;
                }
                int nextStep = OpenclawUpdateProgress.resolveStepFromMessage(message);
                if (nextStep < 0) {
                    mProgressDialog.setStatus(message);
                    return;
                }
                mProgressDialog.setStep(nextStep);
                mProgressDialog.setStatus(message);
            }

            @Override
            public void onError(String error) {
                AnalyticsManager.logEvent(requireContext(), "agent_version_install_failed");
                if (mProgressDialog != null && mProgressDialog.isShowing()) {
                    mProgressDialog.showError(
                        getString(R.string.botdrop_install_failed, error),
                        () -> {
                            mProgressDialog = null;
                            mOpenclawVersionActionInProgress = false;
                        }
                    );
                } else {
                    mOpenclawVersionActionInProgress = false;
                }
            }

            @Override
            public void onComplete(String version) {
                if (mProgressDialog == null) {
                    mOpenclawVersionActionInProgress = false;
                    return;
                }
                AnalyticsManager.logEvent(requireContext(), "agent_version_install_completed");
                mProgressDialog.complete(getString(
                    R.string.botdrop_installation_complete_with_version,
                    TextUtils.isEmpty(version) ? getString(R.string.botdrop_unknown) : version
                ));

                if (getActivity() != null && isAdded()) {
                    getActivity().getWindow().getDecorView().postDelayed(() -> {
                        if (mProgressDialog != null && mProgressDialog.isShowing()) {
                            mProgressDialog.dismiss();
                            mProgressDialog = null;
                        }
                        mOpenclawVersionActionInProgress = false;
                        openDashboard();
                    }, 1500);
                    return;
                }
                mOpenclawVersionActionInProgress = false;
            }
        });
    }

    private void installOpenclawWithSetup(String installVersion) {
        mOpenclawVersionActionInProgress = false;
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_OPENCLAW_VERSION, installVersion).apply();
        TermuxInstaller.createBotDropScripts(ctx, installVersion);

        SetupActivity activity = (SetupActivity) getActivity();
        if (activity != null && !activity.isFinishing()) {
            activity.goToNextStep();
        }
    }

    private void fetchOpenclawVersions(OpenclawVersionUtils.VersionListCallback cb) {
        fetchOpenclawVersionsWithTimeout(cb, OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS, false);
    }

    private void fetchOpenclawVersionsWithTimeout(
        OpenclawVersionUtils.VersionListCallback cb,
        int timeoutSeconds,
        boolean retried
    ) {
        if (cb == null) {
            return;
        }

        if (mBotDropService == null || !mServiceBound) {
            String currentVersion = BotDropService.getOpenclawVersion();
            cb.onResult(
                OpenclawVersionUtils.buildFallback(currentVersion),
                getString(R.string.botdrop_service_not_connected)
            );
            return;
        }

        List<String> cachedVersions = loadOpenclawVersionCache();
        if (cachedVersions != null && !cachedVersions.isEmpty() && isOpenclawVersionCacheFresh()) {
            Logger.logInfo(LOG_TAG, "OpenClaw versions loaded from cache");
            cb.onResult(cachedVersions, null);
            return;
        }

        String currentVersion = BotDropService.getOpenclawVersion();
        mBotDropService.executeCommand(
            OpenclawVersionUtils.VERSIONS_COMMAND,
            timeoutSeconds,
            result -> {
            if (result == null || !result.success) {
                if (!retried) {
                    Logger.logWarn(
                        LOG_TAG,
                        "OpenClaw versions fetch failed, retrying with longer timeout: " +
                        OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS_RETRY + "s"
                    );
                    fetchOpenclawVersionsWithTimeout(cb, OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS_RETRY, true);
                    return;
                }
                if (cachedVersions != null && !cachedVersions.isEmpty()) {
                    cb.onResult(cachedVersions,
                        result == null
                            ? getString(R.string.botdrop_failed_to_fetch_versions)
                            : getString(R.string.botdrop_failed_to_fetch_versions_exit, String.valueOf(result.exitCode))
                    );
                    return;
                }
                cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion),
                    result == null
                        ? getString(R.string.botdrop_failed_to_fetch_versions)
                        : getString(R.string.botdrop_failed_to_fetch_versions_exit, String.valueOf(result.exitCode))
                );
                return;
            }

            List<String> versions = OpenclawVersionUtils.parseVersions(result.stdout);
            if (versions.isEmpty()) {
                if (cachedVersions != null && !cachedVersions.isEmpty()) {
                    cb.onResult(cachedVersions, getString(R.string.botdrop_no_versions_found));
                    return;
                }
                cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), getString(R.string.botdrop_no_versions_found));
                return;
            }

            persistOpenclawVersionCache(versions);
            cb.onResult(versions, null);
            });
    }

    private boolean isOpenclawVersionCacheFresh() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long cacheTime = prefs.getLong(KEY_OPENCLAW_VERSION_CACHE_TIME, 0L);
        if (cacheTime <= 0) {
            return false;
        }
        return System.currentTimeMillis() - cacheTime <= OPENCLAW_VERSION_CACHE_TTL_MS;
    }

    private List<String> loadOpenclawVersionCache() {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String rawCache = prefs.getString(KEY_OPENCLAW_VERSION_CACHE, null);
        if (TextUtils.isEmpty(rawCache)) {
            return null;
        }

        List<String> versions = new ArrayList<>();
        try {
            JSONArray cacheArray = new JSONArray(rawCache);
            for (int i = 0; i < cacheArray.length(); i++) {
                String token = cacheArray.optString(i, null);
                String normalized = OpenclawVersionUtils.normalizeForSort(token);
                if (OpenclawVersionUtils.isStableVersion(normalized)) {
                    versions.add(normalized);
                }
            }
        } catch (Exception e) {
            return null;
        }

        return OpenclawVersionUtils.sortAndLimit(versions);
    }

    private void persistOpenclawVersionCache(List<String> versions) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (versions == null || versions.isEmpty()) {
            prefs.edit().remove(KEY_OPENCLAW_VERSION_CACHE).remove(KEY_OPENCLAW_VERSION_CACHE_TIME).apply();
            return;
        }

        List<String> stableSorted = OpenclawVersionUtils.sortAndLimit(versions);
        JSONArray cacheArray = new JSONArray();
        for (String version : stableSorted) {
            String normalized = OpenclawVersionUtils.normalizeForSort(version);
            if (!TextUtils.isEmpty(normalized)) {
                cacheArray.put(normalized);
            }
        }

        prefs.edit()
            .putString(KEY_OPENCLAW_VERSION_CACHE, cacheArray.toString())
            .putLong(KEY_OPENCLAW_VERSION_CACHE_TIME, System.currentTimeMillis())
            .apply();
    }

    private void showVersionPinDialog() {
        Context ctx = getContext();
        if (ctx == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_OPENCLAW_VERSION, null);
        boolean isPinned = PINNED_VERSION.equals(current);

        if (isPinned) {
            BotDropDialogStyler.createBuilder(ctx)
                .setTitle(R.string.botdrop_openclaw_version_manager_title)
                .setMessage(getString(R.string.botdrop_current_install_version, PINNED_VERSION) + "\n\n"
                    + getString(R.string.botdrop_reset_to_latest) + "?")
                .setPositiveButton(R.string.botdrop_reset_to_latest, (d, w) -> {
                    prefs.edit().remove(KEY_OPENCLAW_VERSION).apply();
                    TermuxInstaller.createBotDropScripts(ctx, "openclaw@latest");
                    Toast.makeText(ctx, getString(R.string.botdrop_set_to_latest, "openclaw@latest"), Toast.LENGTH_SHORT).show();
                    Logger.logInfo(LOG_TAG, "OpenClaw version reset to latest");
                })
                .setNegativeButton(R.string.botdrop_cancel, null)
                .show();
        } else {
            BotDropDialogStyler.createBuilder(ctx)
                .setTitle(R.string.botdrop_openclaw_version_manager_title)
                .setMessage(getString(R.string.botdrop_pin_install_version, PINNED_VERSION))
                .setPositiveButton(R.string.botdrop_pin, (d, w) -> {
                    prefs.edit().putString(KEY_OPENCLAW_VERSION, PINNED_VERSION).apply();
                    TermuxInstaller.createBotDropScripts(ctx, PINNED_VERSION);
                    Toast.makeText(ctx, getString(R.string.botdrop_set_to_latest, PINNED_VERSION), Toast.LENGTH_SHORT).show();
                    Logger.logInfo(LOG_TAG, "OpenClaw version pinned to " + PINNED_VERSION);
                })
                .setNegativeButton(R.string.botdrop_cancel, null)
                .show();
        }
    }

    private void openDashboard() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        Intent dashboardIntent = new Intent(ctx, DashboardActivity.class);
        startActivity(dashboardIntent);
        android.app.Activity activity = getActivity();
        if (activity instanceof SetupActivity && !activity.isFinishing()) {
            activity.finish();
        }
    }
}
