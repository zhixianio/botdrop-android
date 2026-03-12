package app.botdrop;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.content.Context;
import android.widget.Button;

/**
 * Lifecycle callback contract for dashboard runtime mode fragments.
 */
public interface DashboardRuntimeModeSection {

    void onRuntimeModeActivated();

    void onRuntimeModeDeactivated();

    void onServiceConnected(@Nullable BotDropService service);

    void onServiceDisconnected();

    int getRuntimeRunningTextRes();

    int getRuntimeStoppedTextRes();

    boolean isOpenclawWebUiEnabled();

    boolean supportsGatewayErrorMonitoring();

    void queryRuntimeStatus(@NonNull BotDropService service, @NonNull RuntimeStatusListener listener);

    void onRuntimeStatusUpdated(boolean isRunning, @Nullable String uptimeText);

    default void onRuntimeErrorChanged(@Nullable String message) {
        // No-op by default.
    }

    void onStartRuntime(@NonNull Context context, @NonNull BotDropService service,
                        @NonNull Button startButton, @NonNull Runnable onSuccess);

    void onStopRuntime(@NonNull Context context, @NonNull BotDropService service,
                       @NonNull Button stopButton, @NonNull Runnable onSuccess);

    void onRestartRuntime(@NonNull Context context, @NonNull BotDropService service,
                          @NonNull Button restartButton, @NonNull Runnable onSuccess);

    default void onRestartAfterModelChange(
        @NonNull Context context, @NonNull BotDropService service, @NonNull Runnable onSuccess
    ) {
        onSuccess.run();
    }

    interface RuntimeStatusListener {
        void onStatusResult(boolean isRunning, @Nullable String uptimeText);
    }
}
