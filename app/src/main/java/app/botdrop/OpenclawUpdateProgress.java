package app.botdrop;

import android.text.TextUtils;

public final class OpenclawUpdateProgress {

    public static final int STEP_STOPPING_GATEWAY = 0;
    public static final int STEP_INSTALLING_UPDATE = 1;
    public static final int STEP_FINALIZING = 2;
    public static final int STEP_STARTING_GATEWAY = 3;
    public static final int STEP_REFRESHING_MODELS = 4;
    public static final int STEP_UNKNOWN = -1;

    private OpenclawUpdateProgress() {}

    public static int resolveStepFromMessage(String message) {
        if (TextUtils.isEmpty(message)) {
            return STEP_UNKNOWN;
        }

        String normalized = message.trim();
        while (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        while (normalized.endsWith("…")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }

        if (normalized.startsWith("Stopping gateway")) {
            return STEP_STOPPING_GATEWAY;
        }
        if (normalized.startsWith("Installing update")) {
            return STEP_INSTALLING_UPDATE;
        }
        if (normalized.startsWith("Patching Koffi") || normalized.startsWith("Finalizing")) {
            return STEP_FINALIZING;
        }
        if (normalized.startsWith("Starting gateway")) {
            return STEP_STARTING_GATEWAY;
        }
        if (normalized.startsWith("Refreshing")) {
            return STEP_REFRESHING_MODELS;
        }
        if (normalized.contains("停止网关")) {
            return STEP_STOPPING_GATEWAY;
        }
        if (normalized.contains("安装更新") || normalized.contains("正在安装更新")) {
            return STEP_INSTALLING_UPDATE;
        }
        if (normalized.contains("完成") || normalized.contains("正在完成")) {
            return STEP_FINALIZING;
        }
        if (normalized.contains("启动网关")) {
            return STEP_STARTING_GATEWAY;
        }
        if (normalized.contains("刷新模型")) {
            return STEP_REFRESHING_MODELS;
        }

        return STEP_UNKNOWN;
    }
}

