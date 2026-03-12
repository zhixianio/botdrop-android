package com.termux.app;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

public final class AnalyticsManager {

    private static final String LOG_TAG = "AnalyticsManager";
    private static final String DEBUG_PREFS_NAME = "analytics_debug";
    private static final String DEBUG_KEY_LAST_TYPE = "last_type";
    private static final String DEBUG_KEY_LAST_NAME = "last_name";
    private static final String DEBUG_KEY_LAST_PARAMS = "last_params";
    private static final String DEBUG_KEY_LAST_AT = "last_at";
    private static final String DEBUG_KEY_HISTORY = "history";
    private static final int DEBUG_HISTORY_MAX_CHARS = 4000;

    private AnalyticsManager() {}

    public static void applyCollectionState(@NonNull Context context) {
        FirebaseAnalytics analytics = getFirebaseAnalytics(context);
        if (analytics == null) {
            return;
        }

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        boolean enabled = preferences != null
            ? preferences.isAnalyticsCollectionEnabled(BuildConfig.ANALYTICS_DEFAULT_ENABLED)
            : BuildConfig.ANALYTICS_DEFAULT_ENABLED;

        analytics.setAnalyticsCollectionEnabled(enabled);
        Logger.logInfo(LOG_TAG, "Firebase Analytics collection " + (enabled ? "enabled" : "disabled") + ".");
    }

    public static void logScreen(@NonNull Context context, @NonNull String screenName, @NonNull String screenClass) {
        Bundle params = new Bundle();
        params.putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName);
        params.putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass);
        recordDebugSignal(context, "screen", screenName, params);
        Logger.logInfo(LOG_TAG, "logScreen screenName=" + screenName + ", screenClass=" + screenClass);
        logEvent(context, FirebaseAnalytics.Event.SCREEN_VIEW, params);
    }

    public static void logEvent(@NonNull Context context, @NonNull String eventName) {
        logEvent(context, eventName, (Bundle) null);
    }

    public static void logEvent(
        @NonNull Context context,
        @NonNull String eventName,
        @Nullable String paramName,
        @Nullable String paramValue
    ) {
        Bundle params = null;
        if (paramName != null && paramValue != null) {
            params = new Bundle();
            params.putString(paramName, paramValue);
        }
        logEvent(context, eventName, params);
    }

    public static void logEvent(@NonNull Context context, @NonNull String eventName, @Nullable Bundle params) {
        recordDebugSignal(context, "event", eventName, params);
        FirebaseAnalytics analytics = getFirebaseAnalytics(context);
        if (analytics == null) {
            return;
        }

        try {
            Logger.logInfo(LOG_TAG, "logEvent name=" + eventName + ", params=" + bundleToLogString(params));
            analytics.logEvent(eventName, params);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to log Firebase Analytics event " + eventName + ": " + e.getMessage());
        }
    }

    @Nullable
    private static FirebaseAnalytics getFirebaseAnalytics(@NonNull Context context) {
        if (FirebaseApp.initializeApp(context) == null) {
            Logger.logInfo(LOG_TAG, "Skipping Firebase Analytics init: google-services.json config not found.");
            return null;
        }
        return FirebaseAnalytics.getInstance(context);
    }

    @NonNull
    private static String bundleToLogString(@Nullable Bundle params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");
        for (String key : params.keySet()) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(key).append("=").append(params.get(key));
        }
        builder.append("}");
        return builder.toString();
    }

    private static void recordDebugSignal(@NonNull Context context, @NonNull String type, @NonNull String name, @Nullable Bundle params) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            String paramsString = bundleToLogString(params);
            long now = System.currentTimeMillis();
            String entry = now + "|" + type + "|" + name + "|" + paramsString;
            android.content.SharedPreferences prefs = context.getSharedPreferences(DEBUG_PREFS_NAME, Context.MODE_PRIVATE);
            String history = prefs.getString(DEBUG_KEY_HISTORY, "");
            if (history == null || history.isEmpty()) {
                history = entry;
            } else {
                history = history + "\n" + entry;
            }
            if (history.length() > DEBUG_HISTORY_MAX_CHARS) {
                history = history.substring(history.length() - DEBUG_HISTORY_MAX_CHARS);
            }
            prefs.edit()
                .putString(DEBUG_KEY_LAST_TYPE, type)
                .putString(DEBUG_KEY_LAST_NAME, name)
                .putString(DEBUG_KEY_LAST_PARAMS, paramsString)
                .putLong(DEBUG_KEY_LAST_AT, now)
                .putString(DEBUG_KEY_HISTORY, history)
                .apply();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to record analytics debug signal: " + e.getMessage());
        }
    }
}
