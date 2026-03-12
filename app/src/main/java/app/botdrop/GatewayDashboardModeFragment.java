package app.botdrop;

import android.os.Bundle;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.termux.R;

/**
 * Gateway mode control section for dashboard.
 */
public class GatewayDashboardModeFragment extends Fragment implements DashboardRuntimeModeSection {

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mCurrentModelText;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
    private TextView mFeishuStatus;
    private TextView mQQBotStatus;
    private View mRuntimeErrorBanner;
    private TextView mRuntimeErrorText;
    private View mTelegramChannelRow;
    private View mDiscordChannelRow;
    private View mFeishuChannelRow;
    private View mQQBotChannelRow;
    private GatewayDashboardConfigController mGatewayDashboardConfigController;
    private boolean mNeedConfigRefresh;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_gateway_runtime_mode, container, false);

        mStatusText = root.findViewById(R.id.status_text);
        mUptimeText = root.findViewById(R.id.uptime_text);
        mStatusIndicator = root.findViewById(R.id.status_indicator);
        mRuntimeErrorBanner = root.findViewById(R.id.gateway_error_banner);
        mRuntimeErrorText = root.findViewById(R.id.gateway_error_text);
        mCurrentModelText = root.findViewById(R.id.current_model_text);
        mTelegramStatus = root.findViewById(R.id.telegram_status);
        mDiscordStatus = root.findViewById(R.id.discord_status);
        mFeishuStatus = root.findViewById(R.id.feishu_status);
        mQQBotStatus = root.findViewById(R.id.qqbot_status);
        mTelegramChannelRow = root.findViewById(R.id.telegram_channel_row);
        mDiscordChannelRow = root.findViewById(R.id.discord_channel_row);
        mFeishuChannelRow = root.findViewById(R.id.feishu_channel_row);
        mQQBotChannelRow = root.findViewById(R.id.qqbot_channel_row);
        Button changeModelButton = root.findViewById(R.id.btn_change_model);

        if (mCurrentModelText != null && mTelegramStatus != null && mDiscordStatus != null) {
            mGatewayDashboardConfigController = new GatewayDashboardConfigController(
                requireActivity(),
                mCurrentModelText,
                mTelegramStatus,
                mDiscordStatus,
                mFeishuStatus,
                mQQBotStatus
            );
            if (mNeedConfigRefresh) {
                refreshGatewayConfig();
            }
        }

        if (changeModelButton != null) {
            changeModelButton.setOnClickListener(v -> showModelSelector());
        }
        if (mTelegramChannelRow != null) {
            mTelegramChannelRow.setOnClickListener(v ->
                openChannelConfig(ChannelConfigMeta.PLATFORM_TELEGRAM));
        }
        if (mDiscordChannelRow != null) {
            mDiscordChannelRow.setOnClickListener(v ->
                openChannelConfig(ChannelConfigMeta.PLATFORM_DISCORD));
        }
        if (mFeishuChannelRow != null) {
            mFeishuChannelRow.setOnClickListener(v ->
                openChannelConfig(ChannelConfigMeta.PLATFORM_FEISHU));
        }
        if (mQQBotChannelRow != null) {
            mQQBotChannelRow.setOnClickListener(v ->
                openChannelConfig(ChannelConfigMeta.PLATFORM_QQBOT));
        }
        return root;
    }

    @Override
    public void onRuntimeModeActivated() {
        refreshGatewayConfig();
    }

    @Override
    public void onRuntimeModeDeactivated() {
        // No-op.
    }

    @Override
    public void onServiceConnected(@Nullable BotDropService service) {
        refreshGatewayConfig();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mNeedConfigRefresh) {
            refreshGatewayConfig();
        }
    }

    @Override
    public void onServiceDisconnected() {
        // No-op.
    }

    @Override
    public void onRuntimeStatusUpdated(boolean isRunning, @Nullable String uptimeText) {
        if (mStatusText == null || mUptimeText == null || mStatusIndicator == null) {
            return;
        }

        mStatusText.setText(
            getString(isRunning ? getRuntimeRunningTextRes() : getRuntimeStoppedTextRes())
        );
        mStatusIndicator.setBackgroundResource(
            isRunning ? R.drawable.status_indicator_running : R.drawable.status_indicator_stopped
        );
        if (isRunning && !TextUtils.isEmpty(uptimeText)) {
            mUptimeText.setText(getString(R.string.botdrop_uptime, uptimeText));
        } else {
            mUptimeText.setText("—");
        }
    }

    @Override
    public void onRuntimeErrorChanged(@Nullable String message) {
        if (mRuntimeErrorBanner == null || mRuntimeErrorText == null) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            mRuntimeErrorBanner.setVisibility(View.GONE);
            mRuntimeErrorText.setText("—");
            return;
        }
        mRuntimeErrorText.setText(message);
        mRuntimeErrorBanner.setVisibility(View.VISIBLE);
    }

    void refreshGatewayConfig() {
        if (mGatewayDashboardConfigController == null) {
            mNeedConfigRefresh = true;
            return;
        }
        mNeedConfigRefresh = false;
        mGatewayDashboardConfigController.loadCurrentModel();
        mGatewayDashboardConfigController.loadChannelInfo();
    }

    private void showModelSelector() {
        DashboardActivity activity = getDashboardActivity();
        if (activity == null || mGatewayDashboardConfigController == null) {
            return;
        }
        BotDropService service = activity.getCurrentBotDropService();
        if (service == null) {
            Toast.makeText(activity, getString(R.string.botdrop_service_unavailable_try_again), Toast.LENGTH_SHORT).show();
            return;
        }

        mGatewayDashboardConfigController.showModelSelector(service, activity::restartCurrentRuntimeAfterModelChange);
    }

    private DashboardActivity getDashboardActivity() {
        if (!(getActivity() instanceof DashboardActivity)) {
            return null;
        }
        return (DashboardActivity) getActivity();
    }

    private void openChannelConfig(@Nullable String platform) {
        if (mGatewayDashboardConfigController == null) {
            return;
        }
        mGatewayDashboardConfigController.openChannelConfig(platform);
    }

    @Override
    public int getRuntimeRunningTextRes() {
        return R.string.botdrop_gateway_running;
    }

    @Override
    public int getRuntimeStoppedTextRes() {
        return R.string.botdrop_gateway_stopped;
    }

    @Override
    public boolean isOpenclawWebUiEnabled() {
        return true;
    }

    @Override
    public boolean supportsGatewayErrorMonitoring() {
        return true;
    }

    @Override
    public void queryRuntimeStatus(@NonNull BotDropService service, @NonNull RuntimeStatusListener listener) {
        service.isGatewayRunning(result -> {
            if (result == null) {
                listener.onStatusResult(false, null);
                return;
            }
            final boolean running = result.success && "running".equals(result.stdout.trim());
            if (!running) {
                listener.onStatusResult(false, null);
                return;
            }

            service.getGatewayUptime(uptimeResult -> {
                if (uptimeResult == null || !uptimeResult.success) {
                    listener.onStatusResult(true, null);
                    return;
                }

                String uptime = uptimeResult.stdout == null ? "" : uptimeResult.stdout.trim();
                if (uptime == null || uptime.isEmpty() || "—".equals(uptime)) {
                    uptime = null;
                }
                listener.onStatusResult(true, uptime);
            });
        });
    }

    @Override
    public void onStartRuntime(@NonNull Context context, @NonNull BotDropService service,
                               @NonNull android.widget.Button startButton, @NonNull Runnable onSuccess) {
        startButton.setEnabled(false);
        Toast.makeText(context, context.getText(R.string.botdrop_gateway_starting), Toast.LENGTH_SHORT).show();
        service.startGateway(result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_gateway_started), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_gateway_start_failed), Toast.LENGTH_SHORT).show();
            startButton.setEnabled(true);
        });
    }

    @Override
    public void onStopRuntime(@NonNull Context context, @NonNull BotDropService service,
                              @NonNull android.widget.Button stopButton, @NonNull Runnable onSuccess) {
        stopButton.setEnabled(false);
        Toast.makeText(context, context.getText(R.string.botdrop_stopping_gateway), Toast.LENGTH_SHORT).show();
        service.stopGateway(result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_gateway_stopped_toast), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_gateway_stop_failed), Toast.LENGTH_SHORT).show();
            stopButton.setEnabled(true);
        });
    }

    @Override
    public void onRestartRuntime(@NonNull Context context, @NonNull BotDropService service,
                                 @NonNull Button restartButton, @NonNull Runnable onSuccess) {
        restartButton.setEnabled(false);
        Toast.makeText(context, context.getText(R.string.botdrop_gateway_restarting), Toast.LENGTH_SHORT).show();
        service.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_gateway_restarted), Toast.LENGTH_SHORT).show();
                onSuccess.run();
                return;
            }

            Toast.makeText(context, context.getText(R.string.botdrop_gateway_restart_failed), Toast.LENGTH_SHORT).show();
            restartButton.setEnabled(true);
        });
    }

    @Override
    public void onRestartAfterModelChange(
        @NonNull Context context, @NonNull BotDropService service, @NonNull Runnable onSuccess
    ) {
        Toast.makeText(context, context.getText(R.string.botdrop_gateway_restarting_with_new_model), Toast.LENGTH_SHORT).show();
        service.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(context, context.getText(R.string.botdrop_gateway_restarted_successfully), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, context.getText(R.string.botdrop_gateway_restart_failed), Toast.LENGTH_SHORT).show();
            }
            onSuccess.run();
        });
    }

}
