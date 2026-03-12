package app.botdrop;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Html;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Base class for channel configuration pages (Telegram/Discord/Feishu).
 */
public abstract class ChannelFormFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFormFragment";

    private ChannelConfigMeta mMeta;
    private Button mOpenSetupBotButton;
    private TextView mTokenLabel;
    private EditText mTokenInput;
    private TextView mOwnerLabel;
    private EditText mOwnerInput;
    private View mOwnerRow;
    private TextView mFeishuUserIdLabel;
    private EditText mFeishuUserIdInput;
    private TextView mFeishuUserIdHelp;
    private View mFeishuUserIdRow;
    private View mDiscordGuildRow;
    private TextView mDiscordGuildLabel;
    private EditText mDiscordGuildInput;
    private Button mConnectButton;
    private Button mSkipButton;
    private TextView mErrorMessage;
    private TextView mSetupHelpText;

    private BotDropService mService;
    private boolean mBound;
    private boolean mServiceBound;
    private boolean mHasExistingConfig;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mMeta = ChannelConfigMeta.forPlatform(getPlatformId());
    }

    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(getLayoutResId(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mOpenSetupBotButton = view.findViewById(R.id.channel_open_setup_bot);
        mTokenLabel = view.findViewById(R.id.channel_token_label);
        mTokenInput = view.findViewById(R.id.channel_token_input);
        mOwnerLabel = view.findViewById(R.id.channel_owner_label);
        mOwnerInput = view.findViewById(R.id.channel_owner_input);
        mOwnerRow = view.findViewById(R.id.channel_owner_row);
        mFeishuUserIdLabel = view.findViewById(R.id.channel_feishu_user_id_label);
        mFeishuUserIdInput = view.findViewById(R.id.channel_feishu_user_id_input);
        mFeishuUserIdHelp = view.findViewById(R.id.channel_feishu_user_id_help);
        mFeishuUserIdRow = view.findViewById(R.id.channel_feishu_user_id_row);
        mDiscordGuildRow = view.findViewById(R.id.channel_discord_guild_id_row);
        mDiscordGuildLabel = view.findViewById(R.id.channel_discord_guild_id_label);
        mDiscordGuildInput = view.findViewById(R.id.channel_discord_guild_id_input);
        mConnectButton = view.findViewById(R.id.channel_connect_button);
        mSkipButton = view.findViewById(R.id.channel_skip_button);
        mErrorMessage = view.findViewById(R.id.channel_error_message);
        mSetupHelpText = view.findViewById(R.id.channel_setup_help_text);

        if (mMeta != null) {
            if (mTokenLabel != null) {
                mTokenLabel.setText(
                    mMeta.tokenLabelRes != 0 ? mMeta.tokenLabelRes : R.string.botdrop_bot_token
                );
            }
            if (mTokenInput != null) {
                mTokenInput.setHint(
                    mMeta.tokenHintRes != 0 ? mMeta.tokenHintRes : R.string.botdrop_bot_token_hint
                );
            }
            if (mOwnerLabel != null) {
                mOwnerLabel.setText(
                    mMeta.ownerLabelRes != 0 ? mMeta.ownerLabelRes : R.string.botdrop_owner_id
                );
            }
            if (mOwnerInput != null) {
                mOwnerInput.setHint(
                    mMeta.ownerHintRes != 0 ? mMeta.ownerHintRes : R.string.botdrop_owner_id_hint
                );
            }
            if (mOwnerRow != null) {
                mOwnerRow.setVisibility(mMeta.showOwnerField ? View.VISIBLE : View.GONE);
            }
            if (mFeishuUserIdRow != null) {
                mFeishuUserIdRow.setVisibility(
                    ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform) ? View.VISIBLE : View.GONE
                );
            }
            if (mFeishuUserIdLabel != null) {
                mFeishuUserIdLabel.setText(R.string.botdrop_feishu_user_id_next_step);
            }
            if (mFeishuUserIdInput != null) {
                mFeishuUserIdInput.setHint(R.string.botdrop_feishu_user_id_hint);
            }
            if (mFeishuUserIdHelp != null) {
                mFeishuUserIdHelp.setText(
                    Html.fromHtml(
                        getString(R.string.botdrop_feishu_setup_steps),
                        Html.FROM_HTML_MODE_COMPACT
                    )
                );
            }
            if (mDiscordGuildRow != null) {
                mDiscordGuildRow.setVisibility(
                    ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform) ? View.VISIBLE : View.GONE
                );
            }
            if (mDiscordGuildLabel != null) {
                mDiscordGuildLabel.setText(R.string.botdrop_guild_id);
            }
            if (mDiscordGuildInput != null) {
                mDiscordGuildInput.setHint(R.string.botdrop_guild_id);
            }
            if (mSetupHelpText != null && mMeta.setupHelpTextRes != 0) {
                mSetupHelpText.setText(
                    Html.fromHtml(getString(mMeta.setupHelpTextRes), Html.FROM_HTML_MODE_COMPACT)
                );
                mSetupHelpText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            }
        }

        mOpenSetupBotButton.setOnClickListener(v -> openSetupBot());
        mConnectButton.setOnClickListener(v -> connect());
        preloadExistingConfig();
        configureSkipAction();

        Logger.logDebug(LOG_TAG, "ChannelFormFragment view created for " + (mMeta == null ? "unknown" : mMeta.platform));
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, BotDropService.class);
        boolean bound = activity.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (bound) {
            mServiceBound = true;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mServiceBound) {
            Activity activity = getActivity();
            if (activity != null) {
                try {
                    activity.unbindService(mConnection);
                } catch (IllegalArgumentException e) {
                    Logger.logDebug(LOG_TAG, "Service was already unbound");
                }
            }
            mServiceBound = false;
            mBound = false;
            mService = null;
        }
    }

    private void openSetupBot() {
        if (mMeta == null || TextUtils.isEmpty(mMeta.setupBotUrl)) {
            return;
        }
        Context context = getContext();
        if (context != null) {
            AnalyticsManager.logEvent(context, "channel_setup_bot_tap", "platform", mMeta.platform);
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mMeta.setupBotUrl));
        startActivity(browserIntent);
    }

    private void connect() {
        if (mMeta == null) {
            return;
        }

        mErrorMessage.setVisibility(View.GONE);

        String token = mTokenInput.getText().toString().trim();
        String ownerId = mOwnerInput != null ? mOwnerInput.getText().toString().trim() : "";
        String feishuUserId = mFeishuUserIdInput != null ? mFeishuUserIdInput.getText().toString().trim() : "";
        String guildId = mDiscordGuildInput != null ? mDiscordGuildInput.getText().toString().trim() : "";

        if (!mMeta.isTokenValid(token)) {
            AnalyticsManager.logEvent(requireContext(), "channel_connect_invalid", "reason", "invalid_token");
            if (ChannelConfigMeta.PLATFORM_TELEGRAM.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_valid_bot_token));
            } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_app_id));
            } else {
                showError(getString(R.string.botdrop_error_enter_token));
            }
            return;
        }

        if (!mMeta.isOwnerValid(ownerId)) {
            AnalyticsManager.logEvent(requireContext(), "channel_connect_invalid", "reason", "invalid_owner");
            if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_app_secret));
            } else {
                showError(getString(R.string.botdrop_error_enter_owner_id));
            }
            return;
        }

        if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
            if (!mMeta.isDiscordGuildIdValid(guildId)) {
                AnalyticsManager.logEvent(requireContext(), "channel_connect_invalid", "reason", "invalid_guild");
                showError(getString(R.string.botdrop_error_enter_guild_id));
                return;
            }
        }

        Context context = getContext();
        if (context != null) {
            AnalyticsManager.logEvent(context, "channel_connect_tap", "platform", mMeta.platform);
        }

        mConnectButton.setEnabled(false);
        mConnectButton.setText(R.string.botdrop_connecting);

        boolean success;
        if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
            success = ChannelSetupHelper.writeChannelConfig(
                mMeta.platform,
                token,
                ownerId,
                guildId,
                null
            );
        } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
            success = ChannelSetupHelper.writeFeishuChannelConfig(
                token,
                ownerId,
                feishuUserId
            );
        } else {
            success = ChannelSetupHelper.writeChannelConfig(
                mMeta.platform,
                token,
                ownerId
            );
        }
        if (!success) {
            if (context != null) {
                AnalyticsManager.logEvent(context, "channel_connect_failed", "platform", mMeta.platform);
            }
            showError(getString(R.string.botdrop_error_write_config));
            resetButton();
            return;
        }

        if (context != null) {
            AnalyticsManager.logEvent(context, "channel_connect_saved", "platform", mMeta.platform);
        }

        if (ChannelConfigMeta.PLATFORM_TELEGRAM.equals(mMeta.platform)) {
            try {
                Context ctx = getContext();
                if (ctx != null) {
                    ConfigTemplate template = ConfigTemplateCache.loadTemplate(ctx);
                    if (template == null) {
                        template = new ConfigTemplate();
                    }
                    template.tgBotToken = token;
                    template.tgUserId = ownerId;
                    ConfigTemplateCache.saveTemplate(ctx, template);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to save template: " + e.getMessage());
            }
        }

        startGateway();
    }

    private void preloadExistingConfig() {
        mHasExistingConfig = false;
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null || mMeta == null) {
                return;
            }

            JSONObject channelConfig = channels.optJSONObject(mMeta.platform);
            if (channelConfig == null) {
                return;
            }

            String token;
            String owner;
            String feishuUserId = null;
            if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                token = extractFeishuAppIdFromChannelConfig(channelConfig);
                owner = extractFeishuAppSecretFromChannelConfig(channelConfig);
                feishuUserId = extractFeishuUserIdFromChannelConfig(channelConfig);
            } else {
                token = channelConfig.optString("botToken", null);
                if (TextUtils.isEmpty(token)) {
                    token = channelConfig.optString("token", null);
                }
                owner = extractOwnerFromChannelConfig(channelConfig);
            }
            String guildId = null;
            String channelId = null;
            JSONObject guilds = channelConfig.optJSONObject("guilds");
            if (guilds != null && guilds.length() > 0) {
                Iterator<String> guildIterator = guilds.keys();
                while (guildIterator.hasNext()) {
                    String guild = guildIterator.next();
                    if (TextUtils.isEmpty(guild)) {
                        continue;
                    }
                    guildId = guild;
                    break;
                }
            }
            if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform) && TextUtils.isEmpty(guildId)) {
                return;
            }


            if (!TextUtils.isEmpty(token)) {
                mHasExistingConfig = true;
                mTokenInput.setText(token.trim());
            }
            if (mOwnerInput != null && !TextUtils.isEmpty(owner)) {
                mOwnerInput.setText(owner.trim());
            }
            if (mFeishuUserIdInput != null && !TextUtils.isEmpty(feishuUserId)) {
                mFeishuUserIdInput.setText(feishuUserId.trim());
            }
            if (mDiscordGuildInput != null && !TextUtils.isEmpty(guildId)) {
                mDiscordGuildInput.setText(guildId.trim());
            }
            if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
                mHasExistingConfig = !TextUtils.isEmpty(token) && !TextUtils.isEmpty(guildId);
            } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                mHasExistingConfig = !TextUtils.isEmpty(token)
                    && !TextUtils.isEmpty(owner);
                String dmPolicy = channelConfig.optString("dmPolicy", "").trim();
                if ("allowlist".equals(dmPolicy) && TextUtils.isEmpty(feishuUserId)) {
                    mHasExistingConfig = false;
                }
            }

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to preload channel config: " + e.getMessage());
        }
    }

    private String extractOwnerFromChannelConfig(JSONObject channelConfig) {
        Object owner = channelConfig.opt("ownerId");
        if (owner != null) {
            return String.valueOf(owner);
        }

        Object ownerFromAllowFrom = channelConfig.opt("allowFrom");
        if (ownerFromAllowFrom instanceof String) {
            return (String) ownerFromAllowFrom;
        }
        if (ownerFromAllowFrom instanceof JSONArray) {
            JSONArray ids = (JSONArray) ownerFromAllowFrom;
            if (ids.length() > 0) {
                Object first = ids.opt(0);
                return first != null ? String.valueOf(first) : null;
            }
        }
        return "";
    }

    private String extractFeishuAppIdFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        JSONObject accounts = channelConfig.optJSONObject("accounts");
        JSONObject mainAccount = accounts != null ? accounts.optJSONObject("main") : null;
        if (mainAccount == null) {
            return "";
        }

        Object appId = mainAccount.opt("appId");
        return appId != null ? String.valueOf(appId) : "";
    }

    private String extractFeishuAppSecretFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        JSONObject accounts = channelConfig.optJSONObject("accounts");
        JSONObject mainAccount = accounts != null ? accounts.optJSONObject("main") : null;
        if (mainAccount == null) {
            return "";
        }

        Object appSecret = mainAccount.opt("appSecret");
        return appSecret != null ? String.valueOf(appSecret) : "";
    }

    private String extractFeishuUserIdFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        Object allowFrom = channelConfig.opt("allowFrom");
        if (allowFrom instanceof String) {
            return (String) allowFrom;
        }
        if (allowFrom instanceof JSONArray) {
            JSONArray ids = (JSONArray) allowFrom;
            if (ids.length() > 0) {
                Object first = ids.opt(0);
                return first != null ? String.valueOf(first) : "";
            }
        }
        return "";
    }

    private void configureSkipAction() {
        if (mSkipButton == null) {
            return;
        }

        if (mHasExistingConfig) {
            mSkipButton.setText(R.string.botdrop_cancel);
            mSkipButton.setOnClickListener(v -> finishChannelSetup());
        } else {
            mSkipButton.setOnClickListener(v -> skipSetup());
        }
    }

    private void startGateway() {
        if (!mBound || mService == null) {
            AnalyticsManager.logEvent(requireContext(), "channel_gateway_failed", "platform", mMeta.platform);
            showError(getString(R.string.botdrop_service_not_ready));
            resetButton();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");
        mService.startGateway(result -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }

            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    return;
                }

                if (result.success) {
                Logger.logInfo(LOG_TAG, "Gateway started successfully");
                AnalyticsManager.logEvent(requireContext(), "channel_gateway_started", "platform", mMeta.platform);
                Context ctx = getContext();
                if (ctx != null) {
                    Toast.makeText(
                        ctx,
                        R.string.botdrop_connected_gateway_starting,
                        Toast.LENGTH_LONG
                    ).show();
                }

                    SetupActivity setupActivity = (SetupActivity) getActivity();
                    if (setupActivity != null && !setupActivity.isFinishing()) {
                        setupActivity.goToNextStep();
                    }
                } else {
                    Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                    AnalyticsManager.logEvent(requireContext(), "channel_gateway_failed", "platform", mMeta.platform);
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = getString(R.string.botdrop_unknown_error_exit_code, result.exitCode);
                    }
                    showError(getString(R.string.botdrop_error_start_gateway, errorMsg));
                    resetButton();
                }
            });
        });
    }

    private void skipSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        String platformLabel = mMeta == null ? getString(R.string.botdrop_this_channel) : getString(mMeta.titleRes);
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        new AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.botdrop_skip_channel_setup_title, platformLabel))
            .setMessage(getString(R.string.botdrop_skip_channel_setup_message, platformLabel))
            .setPositiveButton(R.string.botdrop_skip, (dialog, which) -> {
                Logger.logInfo(LOG_TAG, "User skipped channel setup");
                AnalyticsManager.logEvent(requireContext(), "channel_skip_confirmed", "platform", mMeta.platform);
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                activity.goToNextStep();
            })
            .setNegativeButton(R.string.botdrop_cancel, (dialog, which) -> {
                AnalyticsManager.logEvent(requireContext(), "channel_skip_cancelled", "platform", mMeta.platform);
                dialog.dismiss();
            })
            .show();
    }

    private void finishChannelSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        AnalyticsManager.logEvent(requireContext(), "channel_finish_existing", "platform", mMeta.platform);
        getActivity().finish();
    }

    private void showError(String message) {
        mErrorMessage.setText(message);
        mErrorMessage.setVisibility(View.VISIBLE);
    }

    private void resetButton() {
        mConnectButton.setEnabled(true);
        mConnectButton.setText(R.string.botdrop_connect_start);
    }

    protected abstract String getPlatformId();
    protected abstract int getLayoutResId();
}
