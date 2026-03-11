package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

public final class AnalyticsManager {

    private static final String LOG_TAG = "AnalyticsManager";

    private AnalyticsManager() {}

    public static void applyCollectionState(@NonNull Context context) {
        if (FirebaseApp.initializeApp(context) == null) {
            Logger.logInfo(LOG_TAG, "Skipping Firebase Analytics init: google-services.json config not found.");
            return;
        }

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        boolean enabled = preferences != null
            ? preferences.isAnalyticsCollectionEnabled(BuildConfig.ANALYTICS_DEFAULT_ENABLED)
            : BuildConfig.ANALYTICS_DEFAULT_ENABLED;

        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled);
        Logger.logInfo(LOG_TAG, "Firebase Analytics collection " + (enabled ? "enabled" : "disabled") + ".");
    }
}
