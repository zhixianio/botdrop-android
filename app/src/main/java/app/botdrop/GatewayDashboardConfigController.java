package app.botdrop;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

import com.termux.shared.logger.Logger;

public final class GatewayDashboardConfigController {

    private static final String LOG_TAG = "GatewayDashboardConfig";

    private final Activity mActivity;
    private final TextView mCurrentModelText;
    private final TextView mTelegramStatus;
    private final TextView mDiscordStatus;
    @Nullable
    private final TextView mFeishuStatus;
    @Nullable
    private final TextView mQQBotStatus;

    GatewayDashboardConfigController(
        @NonNull Activity activity,
        @NonNull TextView currentModelText,
        @NonNull TextView telegramStatus,
        @NonNull TextView discordStatus,
        @Nullable TextView feishuStatus,
        @Nullable TextView qqBotStatus
    ) {
        mActivity = activity;
        mCurrentModelText = currentModelText;
        mTelegramStatus = telegramStatus;
        mDiscordStatus = discordStatus;
        mFeishuStatus = feishuStatus;
        mQQBotStatus = qqBotStatus;
    }

    void loadCurrentModel() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            String currentModel = null;

            JSONObject agents = config.optJSONObject("agents");
            if (agents != null) {
                JSONObject defaults = agents.optJSONObject("defaults");
                if (defaults != null) {
                    Object modelObj = defaults.opt("model");
                    if (modelObj instanceof JSONObject) {
                        currentModel = ((JSONObject) modelObj).optString("primary", null);
                    } else if (modelObj instanceof String) {
                        currentModel = (String) modelObj;
                    }
                }
            }

            if (TextUtils.isEmpty(currentModel)) {
                ConfigTemplate template = ConfigTemplateCache.loadTemplate(mActivity);
                if (template != null && !TextUtils.isEmpty(template.model)) {
                    currentModel = template.model;
                }
            }

            if (!TextUtils.isEmpty(currentModel) && !"null".equals(currentModel)) {
                mCurrentModelText.setText(currentModel);
                Logger.logInfo(LOG_TAG, "Current model: " + currentModel);
            } else {
                mCurrentModelText.setText("—");
            }
        } catch (Exception e) {
            mCurrentModelText.setText("—");
            Logger.logError(LOG_TAG, "Failed to load current model: " + e.getMessage());
        }
    }

    void showModelSelector(@NonNull BotDropService service, @NonNull Runnable onModelUpdated) {
        ModelSelectorDialog dialog = new ModelSelectorDialog(mActivity, service, true);
        dialog.show((provider, model, apiKey, baseUrl, availableModels) -> {
            if (provider != null && model != null) {
                String fullModel = provider + "/" + model;
                updateModel(fullModel, apiKey, baseUrl, availableModels, onModelUpdated);
            }
        });
    }

    void loadChannelInfo() {
        mTelegramStatus.setText("○ —");
        mTelegramStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_disconnected));
        mDiscordStatus.setText("○ —");
        mDiscordStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_disconnected));
        if (mFeishuStatus != null) {
            mFeishuStatus.setText("○ —");
            mFeishuStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_disconnected));
        }
        if (mQQBotStatus != null) {
            mQQBotStatus.setText("○ —");
            mQQBotStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_disconnected));
        }

        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null) {
                return;
            }

            if (ChannelSetupHelper.isTelegramConfigured(channels.optJSONObject("telegram"))) {
                mTelegramStatus.setText(mActivity.getString(R.string.botdrop_connected));
                mTelegramStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_connected));
            }

            if (ChannelSetupHelper.isDiscordConfigured(channels.optJSONObject("discord"))) {
                mDiscordStatus.setText(mActivity.getString(R.string.botdrop_connected));
                mDiscordStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_connected));
            }

            if (ChannelSetupHelper.isFeishuConfigured(channels.optJSONObject("feishu")) && mFeishuStatus != null) {
                mFeishuStatus.setText(mActivity.getString(R.string.botdrop_connected));
                mFeishuStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_connected));
            }

            if (ChannelSetupHelper.isQQBotConfigured(channels.optJSONObject("qqbot")) && mQQBotStatus != null) {
                mQQBotStatus.setText(mActivity.getString(R.string.botdrop_connected));
                mQQBotStatus.setTextColor(ContextCompat.getColor(mActivity, R.color.status_connected));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load channel info: " + e.getMessage());
        }
    }

    void openChannelConfig(@Nullable String platform) {
        Intent intent = new Intent(mActivity, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_CHANNEL);
        if (!TextUtils.isEmpty(platform)) {
            intent.putExtra(SetupActivity.EXTRA_CHANNEL_PLATFORM, platform);
        }
        mActivity.startActivity(intent);
    }

    private void updateModel(
        String fullModel,
        String optionalApiKey,
        String optionalBaseUrl,
        List<String> availableModels,
        @NonNull Runnable onModelUpdated
    ) {
        mCurrentModelText.setText(mActivity.getString(R.string.botdrop_updating_model));
        String[] parts = fullModel.split("/", 2);
        if (parts.length != 2) {
            android.widget.Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_invalid_model_format),
                android.widget.Toast.LENGTH_SHORT
            ).show();
            loadCurrentModel();
            return;
        }

        String provider = parts[0];
        String model = parts[1];
        boolean isCustomProvider = !TextUtils.isEmpty(optionalBaseUrl);
        if (isCustomProvider && (availableModels == null || availableModels.isEmpty())) {
            android.widget.Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_no_custom_model_list),
                android.widget.Toast.LENGTH_SHORT
            ).show();
            loadCurrentModel();
            return;
        }

        boolean configured = BotDropConfig.setActiveProvider(
            provider,
            model,
            optionalApiKey,
            isCustomProvider ? optionalBaseUrl : null,
            isCustomProvider ? availableModels : null
        );

        if (!configured) {
            android.widget.Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_failed_update_model_settings),
                android.widget.Toast.LENGTH_SHORT
            ).show();
            Logger.logError(LOG_TAG, "Failed to update model settings for " + fullModel);
            loadCurrentModel();
            return;
        }

        Logger.logInfo(LOG_TAG, "Model updated to: " + fullModel + ", apiKeyUpdated=" + (!TextUtils.isEmpty(optionalApiKey)));

        ConfigTemplate template = ConfigTemplateCache.loadTemplate(mActivity);
        if (template == null) {
            template = new ConfigTemplate();
        }
        template.provider = provider;
        template.model = fullModel;
        if (!TextUtils.isEmpty(optionalApiKey)) {
            template.apiKey = optionalApiKey;
        }
        if (isCustomProvider && availableModels != null && !availableModels.isEmpty()) {
            template.customModels = new ArrayList<>(availableModels);
        } else if (!isCustomProvider) {
            template.customModels = null;
        }
        if (!TextUtils.isEmpty(optionalBaseUrl)) {
            template.baseUrl = optionalBaseUrl;
        } else {
            template.baseUrl = null;
        }
        ConfigTemplateCache.saveTemplate(mActivity, template);

        onModelUpdated.run();
    }
}
