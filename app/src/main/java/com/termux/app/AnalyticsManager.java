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
        FirebaseAnalytics analytics = getFirebaseAnalytics(context);
        if (analytics == null) {
            return;
        }

        try {
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
}
