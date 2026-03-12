package app.botdrop;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;

import java.io.BufferedInputStream;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONException;
import org.json.JSONObject;

import com.termux.shared.termux.TermuxConstants;

/**
 * Setup wizard with 4 steps:
 * Step 0 (STEP_AGENT_SELECT): Agent Selection
 * Step 1 (STEP_INSTALL): Install openclaw
 * Step 2 (STEP_API_KEY): Choose AI + API Key
 * Step 3 (STEP_CHANNEL): Telegram Config
 */

public class SetupActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SetupActivity";
    private static final String BOTDROP_UPDATE_URL = "https://botdrop.app/";
    private static final int OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE = 3002;
    private static final String OPENCLAW_BACKUP_DIRECTORY = "BotDrop/openclaw";
    private static final String OPENCLAW_BACKUP_FILE_PREFIX = "openclaw-config-backup-";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION = ".zip";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION_JSON = ".json";
    private static final String OPENCLAW_HOME_FOLDER = ".openclaw";
    private static final String OPENCLAW_CONFIG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json";
    private static final String OPENCLAW_AUTH_PROFILES_FILE =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";
    private static final String OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY = "openclawConfig";
    private static final String OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY = "authProfiles";
    private static final String BOTDROP_HOME_FOLDER = "botdrop";
    private static final String OPENCLAW_RESTORE_BACKUP_DIR_PREFIX = ".openclaw_restore_backup_";
    private static final String BOTDROP_RESTORE_BACKUP_DIR_PREFIX = ".botdrop_restore_backup_";
    private static final int OPENCLAW_BACKUP_IO_BUFFER_SIZE = 8192;

    /**
     * Interface for fragments to intercept Next button behavior
     */
    public interface StepFragment {
        /**
         * Called when Next is clicked. Return true to handle it internally.
         */
        boolean handleNext();
    }

    // Step constants (Agent selection first, then install)
    public static final int STEP_AGENT_SELECT = 0;  // Step 1: Agent Selection
    public static final int STEP_INSTALL = 1;       // Step 2: Install openclaw
    public static final int STEP_API_KEY = 2;       // Step 3: Choose AI + API Key
    public static final int STEP_CHANNEL = 3;       // Step 4: Telegram config
    private static final int STEP_COUNT = 4;

    // Intent extra for starting at specific step
    public static final String EXTRA_START_STEP = "start_step";
    public static final String EXTRA_CHANNEL_PLATFORM = "channel_platform";

    private ViewPager2 mViewPager;
    private SetupPagerAdapter mAdapter;
    private View mNavigationBar;
    private Button mBackButton;
    private Button mNextButton;
    private Runnable mPendingOpenclawStorageAction;
    private Runnable mPendingOpenclawStorageDeniedAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_setup);

        mViewPager = findViewById(R.id.setup_viewpager);
        mNavigationBar = findViewById(R.id.setup_navigation);
        mBackButton = findViewById(R.id.setup_button_back);
        mNextButton = findViewById(R.id.setup_button_next);
        
        // Setup Open Terminal button if it exists in layout
        Button openTerminalBtn = findViewById(R.id.setup_open_terminal);
        if (openTerminalBtn != null) {
            openTerminalBtn.setOnClickListener(v -> {
                AnalyticsManager.logEvent(this, "setup_terminal_tap", "step", getAnalyticsStepName(mViewPager.getCurrentItem()));
                openTerminal();
            });
        }

        // Set up ViewPager2
        mAdapter = new SetupPagerAdapter(this);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setUserInputEnabled(false); // Disable swipe, only programmatic navigation

        // Start at specified step
        int startStep = getIntent().getIntExtra(EXTRA_START_STEP, STEP_AGENT_SELECT);
        mViewPager.setCurrentItem(startStep, false);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                logStepViewed(position);
            }
        });
        logStepViewed(startStep);

        // Set up navigation buttons (hidden by default, fragments can show if needed)
        mBackButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current > 0) {
                AnalyticsManager.logEvent(SetupActivity.this, "setup_back_tap", "step", getAnalyticsStepName(current));
                mViewPager.setCurrentItem(current - 1);
            }
        });

        mNextButton.setOnClickListener(v -> {
            // Try to let current fragment handle Next first
            Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag("f" + mViewPager.getCurrentItem());
            if (fragment instanceof StepFragment && ((StepFragment) fragment).handleNext()) {
                return; // Fragment handled it
            }

            // Default: advance to next step
            int current = mViewPager.getCurrentItem();
            if (current < STEP_COUNT - 1) {
                AnalyticsManager.logEvent(this, "setup_next_tap", "step", getAnalyticsStepName(current));
                mViewPager.setCurrentItem(current + 1);
            }
        });

        // Setup manual update check button
        Button checkUpdatesBtn = findViewById(R.id.setup_check_updates);
        checkUpdatesBtn.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "setup_update_check_tap", "step", getAnalyticsStepName(mViewPager.getCurrentItem()));
            v.setEnabled(false);
            UpdateChecker.forceCheck(this, (version, url, notes) -> {
                v.setEnabled(true);
                if (version != null && !version.isEmpty()) {
                    new AlertDialog.Builder(this)
                .setTitle(getString(R.string.botdrop_update_update_available))
                .setMessage(getString(R.string.botdrop_update_update_message, version))
                .setPositiveButton(getString(R.string.botdrop_open_browser), (d, w) -> {
                    AnalyticsManager.logEvent(this, "setup_update_open_browser");
                    openBotdropUpdatePage();
                })
                .setNegativeButton(getString(R.string.botdrop_cancel), null)
                .show();
                } else {
                    Toast.makeText(this, getString(R.string.botdrop_no_update_available), Toast.LENGTH_SHORT).show();
                }
            });
        });

        Logger.logDebug(LOG_TAG, "SetupActivity created, starting at step " + startStep);

    }

    private void openBotdropUpdatePage() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BOTDROP_UPDATE_URL));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.botdrop_no_browser_app_found), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Open terminal activity
     */
    public void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }

    private void logStepViewed(int step) {
        AnalyticsManager.logScreen(this, "setup_" + getAnalyticsStepName(step), "SetupActivity");
    }

    private String getAnalyticsStepName(int step) {
        switch (step) {
            case STEP_AGENT_SELECT:
                return "agent_select";
            case STEP_INSTALL:
                return "install";
            case STEP_API_KEY:
                return "auth";
            case STEP_CHANNEL:
                return "channel";
            default:
                return "unknown";
        }
    }

    /**
     * Allow fragments to control navigation bar visibility
     */
    public void setNavigationVisible(boolean visible) {
        mNavigationBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Allow fragments to enable/disable navigation buttons
     */
    public void setBackEnabled(boolean enabled) {
        mBackButton.setEnabled(enabled);
    }

    public void setNextEnabled(boolean enabled) {
        mNextButton.setEnabled(enabled);
    }

    /**
     * Move to next step (called by fragments when they complete)
     */
    public void goToNextStep() {
        int current = mViewPager.getCurrentItem();
        if (current == STEP_INSTALL) {
            continueToNextStepWithOpenclawRestorePrompt(current);
            return;
        }
        continueToNextStep(current);
    }

    private void continueToNextStepWithOpenclawRestorePrompt(int currentStep) {
        runWithOpenclawStoragePermission(
            () -> {
                File latestBackup = getLatestOpenclawBackupFile();
                if (latestBackup == null || !latestBackup.exists()) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.botdrop_no_openclaw_backup_found,
                            getOpenclawBackupDirectory().getAbsolutePath()
                        ),
                        Toast.LENGTH_SHORT
                    ).show();
                    continueToNextStep(currentStep);
                    return;
                }
                showOpenclawRestoreDialog(() -> continueToNextStep(currentStep), latestBackup);
            },
            () -> {
                Toast.makeText(
                    this,
                    getString(R.string.botdrop_backup_permission_denied_with_manual_restore),
                    Toast.LENGTH_SHORT
                ).show();
                continueToNextStep(currentStep);
            }
        );
    }

    private void continueToNextStep(int current) {
        if (current < STEP_COUNT - 1) {
            mViewPager.setCurrentItem(current + 1, true);
        } else {
            // Last step complete → go to dashboard
            Logger.logInfo(LOG_TAG, "Setup complete");
            AnalyticsManager.logEvent(this, "setup_complete");
            Intent intent = new Intent(this, DashboardActivity.class);
            startActivity(intent);
            finish();
        }
    }

    private void showOpenclawRestoreDialog(@NonNull Runnable continueWithoutRestore, @NonNull File backupFile) {
        AnalyticsManager.logEvent(this, "setup_restore_prompt");
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.botdrop_restore_openclaw_setup_title))
            .setMessage(getString(R.string.botdrop_restore_openclaw_setup_message))
            .setPositiveButton(getString(R.string.botdrop_restore_data_button), (dialog, which) -> {
                AnalyticsManager.logEvent(this, "setup_restore_accept");
                runWithOpenclawStoragePermission(
                    () -> restoreOpenclawConfigAndContinue(backupFile, continueWithoutRestore),
                    continueWithoutRestore
                );
            })
            .setNegativeButton(getString(R.string.botdrop_start_from_scratch), (dialog, which) -> {
                AnalyticsManager.logEvent(this, "setup_restore_skip");
                continueWithoutRestore.run();
            })
            .setCancelable(false)
            .show();
    }

    private void restoreOpenclawConfigAndContinue(@NonNull File backupFile, @NonNull Runnable continueWithoutRestore) {
        new Thread(() -> {
            boolean restored = restoreOpenclawBackupFile(backupFile);
            runOnUiThread(() -> {
                if (!restored) {
                    AnalyticsManager.logEvent(this, "setup_restore_failed");
                    Toast.makeText(this, getString(R.string.botdrop_failed_openclaw_backup_restore), Toast.LENGTH_SHORT).show();
                    continueWithoutRestore.run();
                    return;
                }

                AnalyticsManager.logEvent(this, "setup_restore_completed");
                Toast.makeText(this, getString(R.string.botdrop_openclaw_data_restored), Toast.LENGTH_SHORT).show();
                ConfigTemplateCache.clearTemplate(this);
                Intent intent = new Intent(this, DashboardActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action, @NonNull Runnable deniedAction) {
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
            getOpenclawBackupDirectory().getAbsolutePath(),
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
        mPendingOpenclawStorageAction = null;
        mPendingOpenclawStorageDeniedAction = null;

        if (action == null) {
            return;
        }

        if (!isOpenclawStoragePermissionGranted()) {
            if (deniedAction != null) {
                deniedAction.run();
            }
            return;
        }

        action.run();
    }

    private boolean restoreOpenclawBackupFile(@NonNull File backupFile) {
        if (backupFile.getName().endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON)) {
            return applyLegacyOpenclawBackup(backupFile);
        }

        if (!backupFile.exists()) {
            return false;
        }

        File openclawDir = getOpenclawHomeDirectory();
        File botdropDir = getBotdropHomeDirectory();
        if (openclawDir == null || botdropDir == null) {
            return false;
        }

        File homeDir = getOpenclawHomeParentDirectory();
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

    private boolean isOpenclawStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Environment.isExternalStorageManager();
        }
        return PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this));
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

                try (FileOutputStream out = new FileOutputStream(targetFile)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }

            return restoredAny;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to extract OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private File createOpenclawRestoreStagingDirectory(@NonNull File homeDir) {
        for (int suffix = 0; suffix < 10; suffix++) {
            File stagingDir = new File(homeDir, ".openclaw_restore_staging_" + System.currentTimeMillis() + "_" + suffix);
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

    @Nullable
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
        } catch (IOException | JSONException e) {
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
        } catch (IOException | JSONException e) {
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

        if (normalized.equals(OPENCLAW_HOME_FOLDER) || normalized.equals(OPENCLAW_HOME_FOLDER + "/")
            || normalized.equals(BOTDROP_HOME_FOLDER) || normalized.equals(BOTDROP_HOME_FOLDER + "/")) {
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

        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.equals("..") || normalized.startsWith("../") || normalized.endsWith("/..") || normalized.contains("/../")) {
            return null;
        }

        return normalized;
    }

    private File getOpenclawBackupDirectory() {
        File documentsDir = Environment.getExternalStorageDirectory();
        return new File(documentsDir, OPENCLAW_BACKUP_DIRECTORY);
    }

    /**
     * ViewPager2 adapter for setup steps
     */
    private static class SetupPagerAdapter extends FragmentStateAdapter {

        public SetupPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case STEP_AGENT_SELECT:
                    return new AgentSelectionFragment();
                case STEP_INSTALL:
                    return new InstallFragment();
                case STEP_API_KEY:
                    return new AuthFragment();
                case STEP_CHANNEL:
                    return new ChannelFragment();
                default:
                    throw new IllegalArgumentException("Invalid step: " + position);
            }
        }

        @Override
        public int getItemCount() {
            return STEP_COUNT;
        }
    }
}
