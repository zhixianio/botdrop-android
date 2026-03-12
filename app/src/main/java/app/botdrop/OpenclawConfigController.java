package app.botdrop;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.android.PermissionUtils;

import java.io.File;

public final class OpenclawConfigController {

    private static final int OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE = 3001;

    private final DashboardActivity mActivity;
    private final android.os.Handler mHandler;
    private final TextView mBackupButton;
    private final TextView mRestoreButton;
    @Nullable
    private final Runnable mOnConfigRestoredOrFailed;

    private Runnable mPendingOpenclawStorageAction;
    private Runnable mPendingOpenclawStorageDeniedAction;

    public OpenclawConfigController(
        @NonNull DashboardActivity activity,
        @NonNull android.os.Handler handler,
        @Nullable TextView backupButton,
        @Nullable TextView restoreButton,
        @Nullable Runnable onConfigRestoredOrFailed
    ) {
        mActivity = activity;
        mHandler = handler;
        mBackupButton = backupButton;
        mRestoreButton = restoreButton;
        mOnConfigRestoredOrFailed = onConfigRestoredOrFailed;
    }

    public void backupOpenclawConfigToSdcard() {
        runWithOpenclawStoragePermission(() -> {
            setButtonEnabled(mBackupButton, false);
            new Thread(() -> {
                String backupPath = OpenclawBackupOperations.createOpenclawBackupFile();
                mHandler.post(() -> {
                    setButtonEnabled(mBackupButton, true);
                    if (TextUtils.isEmpty(backupPath)) {
                        Toast.makeText(
                            mActivity,
                            mActivity.getString(R.string.botdrop_no_openclaw_data_folder),
                            Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    Toast.makeText(
                        mActivity,
                        mActivity.getString(R.string.botdrop_openclaw_backup_created, backupPath),
                        Toast.LENGTH_LONG
                    ).show();
                });
            }).start();
        }, () -> {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_backup_permission_denied),
                Toast.LENGTH_SHORT
            ).show();
        });
    }

    public void restoreOpenclawConfigFromSdcard() {
        runWithOpenclawStoragePermission(() -> {
            File backupFile = OpenclawBackupOperations.getLatestOpenclawBackupFile();
            if (backupFile == null) {
                Toast.makeText(
                    mActivity,
                    mActivity.getString(
                        R.string.botdrop_no_backup_found,
                        OpenclawBackupOperations.getOpenclawBackupDirectory().getAbsolutePath()
                    ),
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }

            confirmOpenclawRestore(backupFile);
        }, () -> Toast.makeText(
            mActivity,
            mActivity.getString(R.string.botdrop_backup_permission_denied),
            Toast.LENGTH_SHORT
        ).show());
    }

    public void onRequestPermissionsResult() {
        retryPendingOpenclawStorageActionIfPermitted();
    }

    public void onActivityResult() {
        retryPendingOpenclawStorageActionIfPermitted();
    }

    public void onDestroy() {
        mPendingOpenclawStorageAction = null;
        mPendingOpenclawStorageDeniedAction = null;
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action) {
        runWithOpenclawStoragePermission(action, null);
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action, @Nullable Runnable deniedAction) {
        File backupDir = OpenclawBackupOperations.getOpenclawBackupDirectory();
        if (isOpenclawStoragePermissionGranted()) {
            action.run();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (PermissionUtils.requestManageStorageExternalPermission(
                mActivity,
                OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE
            ) == null) {
                mPendingOpenclawStorageAction = action;
                mPendingOpenclawStorageDeniedAction = deniedAction;
            } else if (deniedAction != null) {
                deniedAction.run();
            }
            return;
        }

        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermissionIfPathOnPrimaryExternalStorage(
            mActivity,
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
                    mActivity,
                    mActivity.getString(R.string.botdrop_backup_permission_denied),
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

        return PermissionUtils.checkStoragePermission(
            mActivity,
            PermissionUtils.isLegacyExternalStoragePossible(mActivity)
        );
    }

    private void confirmOpenclawRestore(File backupFile) {
        if (backupFile == null) {
            return;
        }

        String createdAtText = OpenclawBackupOperations.formatBackupTimestamp(
            OpenclawBackupOperations.readBackupCreatedAt(backupFile)
        );
        String message = mActivity.getString(
            R.string.botdrop_restore_openclaw_data_message,
            backupFile.getName(),
            createdAtText
        );

        new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.botdrop_restore_openclaw_data))
            .setMessage(message)
            .setNegativeButton(mActivity.getString(R.string.botdrop_cancel), null)
            .setPositiveButton(mActivity.getString(R.string.botdrop_restore), (dialog, which) -> performOpenclawRestore(backupFile))
            .show();
    }

    private void performOpenclawRestore(File backupFile) {
        if (backupFile == null) {
            return;
        }

        setButtonEnabled(mRestoreButton, false);
        new Thread(() -> {
            boolean restored = OpenclawBackupOperations.applyOpenclawBackup(backupFile);
            mHandler.post(() -> {
                setButtonEnabled(mRestoreButton, true);
                if (!restored) {
                    Toast.makeText(
                        mActivity,
                        mActivity.getString(R.string.botdrop_failed_openclaw_backup_restore),
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                if (mOnConfigRestoredOrFailed != null) {
                    mOnConfigRestoredOrFailed.run();
                }
                Toast.makeText(
                    mActivity,
                    mActivity.getString(R.string.botdrop_openclaw_data_restored),
                    Toast.LENGTH_LONG
                ).show();
            });
        }).start();
    }

    private void setButtonEnabled(TextView button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
    }
}
