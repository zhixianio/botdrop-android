package app.botdrop;

import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpenclawUpdateController {

    private static final String MODEL_LIST_COMMAND = "openclaw models list --all --plain";
    private static final String MODEL_PREFS_NAME = "openclaw_model_cache_v1";
    private static final String MODEL_CACHE_KEY_PREFIX = "models_by_version_";
    private static final int OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS = 180;

    private final DashboardActivity mActivity;
    private final android.os.Handler mHandler;
    private TextView mOpenclawVersionText;
    private final TextView mOpenclawCheckUpdateButton;
    private final android.widget.Button mStartButton;
    private final android.widget.Button mStopButton;
    private final android.widget.Button mRestartButton;
    private final Runnable mRefreshStatus;

    private AlertDialog mOpenclawUpdateDialog;
    private AlertDialog mOpenclawVersionManagerDialog;
    private String mOpenclawLatestUpdateVersion;
    private boolean mOpenclawManualCheckRequested;
    private boolean mOpenclawVersionActionInProgress;

    interface ModelListPrefetchCallback {
        void onFinished(boolean success);
    }

    public OpenclawUpdateController(@NonNull DashboardActivity activity,
                                   @NonNull android.os.Handler handler,
                                   @Nullable TextView openclawVersionText,
                                   @Nullable TextView openclawCheckUpdateButton,
                                   @NonNull android.widget.Button startButton,
                                   @NonNull android.widget.Button stopButton,
                                   @NonNull android.widget.Button restartButton,
                                   @NonNull Runnable refreshStatus) {
        mActivity = activity;
        mHandler = handler;
        mOpenclawVersionText = openclawVersionText;
        mOpenclawCheckUpdateButton = openclawCheckUpdateButton;
        mStartButton = startButton;
        mStopButton = stopButton;
        mRestartButton = restartButton;
        mRefreshStatus = refreshStatus;
    }

    public void setOpenclawVersionText(@Nullable TextView openclawVersionText) {
        mOpenclawVersionText = openclawVersionText;
        if (mOpenclawVersionText != null) {
            String currentVersion = BotDropService.getOpenclawVersion();
            if (currentVersion != null) {
                mOpenclawVersionText.setText(
                    mActivity.getString(R.string.botdrop_openclaw_version, currentVersion)
                );
            }
        }
    }

    public void onDestroy() {
        dismissOpenclawUpdateDialog();
        if (mOpenclawVersionManagerDialog != null && mOpenclawVersionManagerDialog.isShowing()) {
            mOpenclawVersionManagerDialog.dismiss();
        }
        mOpenclawVersionManagerDialog = null;
        mOpenclawVersionActionInProgress = false;
    }

    public void checkOpenclawUpdate() {
        if (!mActivity.isBoundToBotDropService()) {
            return;
        }
        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            return;
        }

        // One-time migration: clear stale throttle from previous code that recorded
        // check time even when npm returned invalid output, blocking retries for 24h.
        android.content.SharedPreferences updatePrefs =
            mActivity.getSharedPreferences("openclaw_update", android.content.Context.MODE_PRIVATE);
        if (!updatePrefs.getBoolean("throttle_fix_v1", false)) {
            updatePrefs.edit()
                .remove("last_check_time")
                .putBoolean("throttle_fix_v1", true)
                .apply();
        }

        String currentVersion = BotDropService.getOpenclawVersion();
        if (currentVersion != null && mOpenclawVersionText != null) {
            mOpenclawVersionText.setText(mActivity.getString(R.string.botdrop_openclaw_version, currentVersion));
        }

        String[] stored = OpenClawUpdateChecker.getAvailableUpdate(mActivity);
        if (stored != null) {
            showOpenclawUpdateDialog(stored[0], stored[1], false);
        }

        OpenClawUpdateChecker.check(mActivity, service, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                showOpenclawUpdateDialog(current, latest, false);
            }

            @Override
            public void onNoUpdate() {
                dismissOpenclawUpdateDialog();
            }
        });
    }

    public void forceCheckOpenclawUpdate() {
        if (!mActivity.isBoundToBotDropService()) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawCheckUpdateButton == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_check_button_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }

        mOpenclawCheckUpdateButton.setEnabled(false);
        mOpenclawCheckUpdateButton.setText(mActivity.getString(R.string.botdrop_checking_openclaw));
        mOpenclawLatestUpdateVersion = null;
        mOpenclawManualCheckRequested = true;

        OpenClawUpdateChecker.check(mActivity, service, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText(mActivity.getString(R.string.botdrop_check_openclaw_updates));
                mOpenclawManualCheckRequested = false;
                showOpenclawUpdateDialog(current, latest, true);
            }

            @Override
            public void onNoUpdate() {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText(mActivity.getString(R.string.botdrop_check_openclaw_updates));
                mOpenclawManualCheckRequested = false;
                dismissOpenclawUpdateDialog();
                Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_already_up_to_date), Toast.LENGTH_SHORT).show();
            }
        }, true);
    }

    public void showOpenclawVersionManagerDialog() {
        if (mOpenclawVersionActionInProgress) {
            return;
        }
        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        setOpenclawVersionManagerBusy(true);
        dismissOpenclawUpdateDialog();
        if (mOpenclawVersionManagerDialog != null) {
            mOpenclawVersionManagerDialog.dismiss();
            mOpenclawVersionManagerDialog = null;
        }

        mOpenclawVersionManagerDialog = new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_openclaw_versions))
            .setMessage(mActivity.getString(R.string.botdrop_loading_versions))
            .setCancelable(false)
            .setNegativeButton(R.string.botdrop_cancel, (d, w) -> setOpenclawVersionManagerBusy(false))
            .create();
        mOpenclawVersionManagerDialog.show();

        fetchOpenclawVersions((versions, errorMessage) -> {
                if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                    setOpenclawVersionManagerBusy(false);
                    return;
                }
                if (mOpenclawVersionManagerDialog != null) {
                    mOpenclawVersionManagerDialog.dismiss();
                    mOpenclawVersionManagerDialog = null;
                }

                if (versions == null || versions.isEmpty()) {
                    showOpenclawVersionManagerErrorDialog(
                        TextUtils.isEmpty(errorMessage) ? mActivity.getString(R.string.botdrop_no_versions_available) : errorMessage
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
            message = mActivity.getString(R.string.botdrop_failed_to_load_version_list);
        }

        mOpenclawVersionManagerDialog = new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_openclaw_versions))
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
            showOpenclawVersionManagerErrorDialog(mActivity.getString(R.string.botdrop_no_valid_versions_found));
            return;
        }

        String currentVersion = OpenclawVersionUtils.normalizeForSort(BotDropService.getOpenclawVersion());
        String[] labels = new String[normalized.size()];
        for (int i = 0; i < normalized.size(); i++) {
            String v = normalized.get(i);
            if (!TextUtils.isEmpty(currentVersion) && TextUtils.equals(currentVersion, v)) {
                labels[i] = mActivity.getString(R.string.botdrop_openclaw_current_version, v);
            } else {
                labels[i] = mActivity.getString(R.string.botdrop_openclaw_version, v);
            }
        }

        mOpenclawVersionManagerDialog = new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_openclaw_versions))
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
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_invalid_version_format), Toast.LENGTH_SHORT).show();
            return;
        }

        mOpenclawVersionManagerDialog = new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_install) + " " + mActivity.getString(R.string.botdrop_openclaw))
            .setMessage(mActivity.getString(R.string.botdrop_install_openclaw_confirm, installVersion))
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

        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), mActivity.getString(R.string.botdrop_service_not_connected));
            return;
        }

        service.executeCommand(
            OpenclawVersionUtils.VERSIONS_COMMAND,
            OPENCLAW_VERSION_FETCH_TIMEOUT_SECONDS,
            result -> {
                if (result == null || !result.success) {
                    String fallbackError = result == null
                        ? mActivity.getString(R.string.botdrop_failed_to_fetch_versions)
                        : mActivity.getString(R.string.botdrop_failed_to_fetch_versions_exit, String.valueOf(result.exitCode));
                    cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), fallbackError);
                    return;
                }

                List<String> versions = OpenclawVersionUtils.parseVersions(result.stdout);
                if (versions.isEmpty()) {
                    cb.onResult(OpenclawVersionUtils.buildFallback(currentVersion), mActivity.getString(R.string.botdrop_no_versions_found));
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

    private void showOpenclawUpdateDialog(String currentVersion, String latestVersion, boolean manualCheck) {
        if (TextUtils.isEmpty(latestVersion) || mActivity.isFinishing() || mActivity.isDestroyed()) {
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
        String currentPart = TextUtils.isEmpty(currentVersion) ? mActivity.getString(R.string.botdrop_unknown) : currentVersion;
        String content = mActivity.getString(R.string.botdrop_openclaw_update_available, currentPart, latestVersion);

        dismissOpenclawUpdateDialog();
        final String updateVersion = latestVersion;
        mOpenclawUpdateDialog = new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_update_available))
            .setMessage(content)
            .setCancelable(true)
            .setPositiveButton(R.string.botdrop_update, (d, w) -> startOpenclawUpdate(updateVersion))
            .setNeutralButton(R.string.botdrop_later, null)
            .setNegativeButton(R.string.botdrop_dismiss, (d, w) -> dismissOpenclawUpdate(updateVersion))
            .setOnDismissListener(dialog -> {
                if (mOpenclawUpdateDialog == dialog) {
                    mOpenclawUpdateDialog = null;
                    mOpenclawManualCheckRequested = false;
                }
            })
            .create();
        mOpenclawUpdateDialog.show();
        if (mOpenclawManualCheckRequested) {
            mOpenclawManualCheckRequested = false;
        }
    }

    private void dismissOpenclawUpdate(String version) {
        if (!TextUtils.isEmpty(version)) {
            OpenClawUpdateChecker.dismiss(mActivity, version);
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_dismissed_update, version), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_no_update_target_version), Toast.LENGTH_SHORT).show();
            setOpenclawVersionManagerBusy(false);
            return;
        }

        dismissOpenclawUpdateDialog();
        setOpenclawVersionManagerBusy(true);

        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            setOpenclawVersionManagerBusy(false);
            return;
        }

        if (mOpenclawVersionManagerDialog != null) {
            mOpenclawVersionManagerDialog.dismiss();
            mOpenclawVersionManagerDialog = null;
        }

        View dialogView = mActivity.getLayoutInflater().inflate(R.layout.dialog_openclaw_update, null);
        TextView[] stepIcons = {
            dialogView.findViewById(R.id.update_step_0_icon),
            dialogView.findViewById(R.id.update_step_1_icon),
            dialogView.findViewById(R.id.update_step_2_icon),
            dialogView.findViewById(R.id.update_step_3_icon),
            dialogView.findViewById(R.id.update_step_4_icon),
        };
        TextView statusMessage = dialogView.findViewById(R.id.update_status_message);

        AlertDialog progressDialog = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.botdrop_updating_openclaw)
            .setView(dialogView)
            .setCancelable(false)
            .create();
        progressDialog.show();

        mStartButton.setEnabled(false);
        mStopButton.setEnabled(false);
        mRestartButton.setEnabled(false);

        service.updateOpenclaw(targetVersion,
            new BotDropService.UpdateProgressCallback() {
                private int currentStep = -1;

                private void advanceTo(String message) {
                    int nextStep = OpenclawUpdateProgress.resolveStepFromMessage(message);
                    if (nextStep < 0) return;
                    advanceToStep(nextStep);
                }

                private void advanceToStep(int nextStep) {
                    if (nextStep < 0) return;

                    for (int i = 0; i <= currentStep && i < stepIcons.length; i++) {
                        stepIcons[i].setText("\u2713");
                    }
                    if (nextStep < stepIcons.length) {
                        stepIcons[nextStep].setText("\u25CF");
                    }
                    currentStep = nextStep;
                }

                @Override
                public void onStepStart(String message) {
                    advanceTo(message);
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    setOpenclawVersionManagerBusy(false);
                    mRefreshStatus.run();
                    if (canShowDialog()) {
                        new AlertDialog.Builder(mActivity)
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
                    statusMessage.setText(mActivity.getString(R.string.botdrop_updated_to_version_refreshing, newVersion));
                    prefetchModelsForUpdate(newVersion, success -> {
                        for (TextView icon : stepIcons) {
                            icon.setText("\u2713");
                        }
                        statusMessage.setText(
                            success
                                ? mActivity.getString(R.string.botdrop_updated_to_version, newVersion)
                                : mActivity.getString(R.string.botdrop_updated_to_version_cache_failed, newVersion)
                        );

                        mHandler.postDelayed(() -> {
                            if (canShowDialog()) {
                                progressDialog.dismiss();
                            }
                            setOpenclawVersionManagerBusy(false);
                            OpenClawUpdateChecker.clearUpdate(mActivity);
                            if (mOpenclawVersionText != null) {
                                mOpenclawVersionText.setText(mActivity.getString(R.string.botdrop_openclaw_version, newVersion));
                            }
                            mRefreshStatus.run();
                        }, 1500);
                    });
                }
            });
    }

    private void prefetchModelsForUpdate(String openclawVersion, ModelListPrefetchCallback callback) {
        final ModelListPrefetchCallback finalCallback =
            callback == null ? (ModelListPrefetchCallback) success -> {} : callback;

        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            finalCallback.onFinished(false);
            return;
        }

        final String normalizedVersion = normalizeModelCacheKey(openclawVersion);
        service.executeCommand(MODEL_LIST_COMMAND, result -> {
            if (!result.success) {
                finalCallback.onFinished(false);
                return;
            }

            List<ModelInfo> models = parseModelListForUpdate(result.stdout);
            if (models.isEmpty()) {
                finalCallback.onFinished(false);
                return;
            }

            Collections.sort(models, (a, b) -> {
                if (a == null || b == null || a.fullName == null || b.fullName == null) return 0;
                return b.fullName.compareToIgnoreCase(a.fullName);
            });

            cacheModelsForUpdate(normalizedVersion, models);
            finalCallback.onFinished(true);
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
            // No-op.
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

            mActivity.getSharedPreferences(MODEL_PREFS_NAME, mActivity.MODE_PRIVATE)
                .edit()
                .putString(modelCacheKey(version), root.toString())
                .apply();
        } catch (Exception ignored) {
            // No-op.
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

    private boolean canShowDialog() {
        return !mActivity.isFinishing() && !mActivity.isDestroyed();
    }
}
