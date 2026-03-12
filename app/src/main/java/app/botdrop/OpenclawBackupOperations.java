package app.botdrop;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;
import org.json.JSONException;

import android.os.Environment;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class OpenclawBackupOperations {

    private static final String LOG_TAG = "OpenclawBackupOperations";

    public static final String OPENCLAW_HOME_FOLDER = ".openclaw";
    public static final String BOTDROP_HOME_FOLDER = "botdrop";

    public static final String OPENCLAW_BACKUP_DIRECTORY = "BotDrop/openclaw";
    public static final String OPENCLAW_BACKUP_FILE_PREFIX = "openclaw-config-backup-";
    public static final String OPENCLAW_BACKUP_FILE_EXTENSION = ".zip";
    public static final String OPENCLAW_BACKUP_FILE_EXTENSION_JSON = ".json";
    public static final String OPENCLAW_BACKUP_DATE_PATTERN = "yyyyMMdd_HHmmss";
    public static final String OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY = "openclawConfig";
    public static final String OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY = "authProfiles";
    public static final int OPENCLAW_BACKUP_IO_BUFFER_SIZE = 8192;
    private static final int OPENCLAW_BACKUP_DIR_PREFIX_RANDOM_ATTEMPTS = 10;

    public static final String OPENCLAW_CONFIG_FILE =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json";
    public static final String OPENCLAW_AUTH_PROFILES_FILE =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";

    private static final String OPENCLAW_RESTORE_STAGING_DIR_PREFIX = ".openclaw_restore_staging_";
    private static final String OPENCLAW_RESTORE_BACKUP_DIR_PREFIX = ".openclaw_restore_backup_";
    private static final String BOTDROP_RESTORE_BACKUP_DIR_PREFIX = ".botdrop_restore_backup_";

    private OpenclawBackupOperations() {
        // no-op
    }

    public static File getOpenclawHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, OPENCLAW_HOME_FOLDER);
    }

    public static File getBotdropHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BOTDROP_HOME_FOLDER);
    }

    @Nullable
    public static File getOpenclawHomeParentDirectory() {
        File openclawDir = getOpenclawHomeDirectory();
        if (openclawDir == null || openclawDir.getParentFile() == null) {
            return null;
        }
        return openclawDir.getParentFile();
    }

    public static File getOpenclawBackupDirectory() {
        File documentsDir = Environment.getExternalStorageDirectory();
        return new File(documentsDir, OPENCLAW_BACKUP_DIRECTORY);
    }

    public static String formatBackupTimestamp(long timeMs) {
        if (timeMs <= 0L) {
            timeMs = System.currentTimeMillis();
        }
        return new SimpleDateFormat(OPENCLAW_BACKUP_DATE_PATTERN, Locale.US).format(new Date(timeMs));
    }

    public static long readBackupCreatedAt(@NonNull File backupFile) {
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

    public static File getLatestOpenclawBackupFile() {
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

    public static String createOpenclawBackupFile() {
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

    public static boolean createOpenclawBackupZip(
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
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create backup zip", e);
            return false;
        }
    }

    public static boolean addOpenclawDirectoryEntriesToZip(
        @NonNull File sourceDir,
        @NonNull File current,
        @NonNull ZipOutputStream zos,
        @NonNull byte[] buffer
    ) throws IOException {
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

    public static boolean extractOpenclawBackupToDirectory(@NonNull File backupFile, @NonNull File homeDir) {
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

    public static boolean applyOpenclawBackup(@NonNull File backupFile) {
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
                Logger.logWarn(LOG_TAG, "No re-storable data directory found in backup");
                return false;
            }

            for (int i = 0; i < rollbackDirs.length; i++) {
                if (rollbackDirs[i] != null && rollbackDirs[i].exists()) {
                    if (!deleteRecursively(rollbackDirs[i])) {
                        Logger.logWarn(LOG_TAG, "Failed to delete previous backup backup directory for " + restoreTargets[i].getName());
                    }
                }
            }
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

    public static boolean applyLegacyOpenclawBackup(@NonNull File backupFile) {
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

    public static boolean isLegacyOpenclawBackupFile(@NonNull File backupFile) {
        return backupFile.getName().endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON);
    }

    @Nullable
    public static File createOpenclawRestoreStagingDirectory(@NonNull File homeDir) {
        for (int suffix = 0; suffix < OPENCLAW_BACKUP_DIR_PREFIX_RANDOM_ATTEMPTS; suffix++) {
            File stagingDir = new File(homeDir, OPENCLAW_RESTORE_STAGING_DIR_PREFIX + System.currentTimeMillis() + "_" + suffix);
            if (!stagingDir.exists()) {
                return stagingDir;
            }
        }
        return null;
    }

    @Nullable
    public static File createOpenclawRollbackDirectory(@NonNull File homeDir, @NonNull String targetName) {
        String prefix = OPENCLAW_HOME_FOLDER.equals(targetName)
            ? OPENCLAW_RESTORE_BACKUP_DIR_PREFIX
            : BOTDROP_RESTORE_BACKUP_DIR_PREFIX;
        for (int suffix = 0; suffix < OPENCLAW_BACKUP_DIR_PREFIX_RANDOM_ATTEMPTS; suffix++) {
            File rollbackDir = new File(homeDir, prefix + System.currentTimeMillis() + "_" + suffix);
            if (!rollbackDir.exists()) {
                return rollbackDir;
            }
        }
        return null;
    }

    public static JSONObject readJsonFromFile(@NonNull File file) {
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

    public static boolean writeJsonToFile(@NonNull File file, @NonNull JSONObject payload) {
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

    public static boolean deleteRecursively(@NonNull File file) {
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
    public static String normalizeOpenclawBackupEntryPath(@Nullable String entryName) {
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

    public static boolean isOpenclawBackupDirectoryReady() {
        return getOpenclawBackupDirectory().exists();
    }
}
