package app.botdrop;

import com.termux.R;

/**
 * Static metadata and validation rules for IM channel configuration pages.
 */
public class ChannelConfigMeta {

    public static final String PLATFORM_TELEGRAM = "telegram";
    public static final String PLATFORM_DISCORD = "discord";
    public static final String PLATFORM_FEISHU = "feishu";
    public static final String PLATFORM_QQBOT = "qqbot";

    public final String platform;
    public final int titleRes;
    public final String setupBotUrl;
    public final int tokenLabelRes;
    public final int tokenHintRes;
    public final int ownerLabelRes;
    public final int ownerHintRes;
    public final int setupHelpTextRes;
    public final boolean showOwnerField;

    private ChannelConfigMeta(
        String platform,
        int titleRes,
        String setupBotUrl,
        int tokenLabelRes,
        int tokenHintRes,
        int ownerLabelRes,
        int ownerHintRes,
        int setupHelpTextRes,
        boolean showOwnerField
    ) {
        this.platform = platform;
        this.titleRes = titleRes;
        this.setupBotUrl = setupBotUrl;
        this.tokenLabelRes = tokenLabelRes;
        this.tokenHintRes = tokenHintRes;
        this.ownerLabelRes = ownerLabelRes;
        this.ownerHintRes = ownerHintRes;
        this.setupHelpTextRes = setupHelpTextRes;
        this.showOwnerField = showOwnerField;
    }

    public static ChannelConfigMeta forPlatform(String platform) {
        if (PLATFORM_DISCORD.equals(platform)) {
            return discord();
        }
        if (PLATFORM_FEISHU.equals(platform)) {
            return feishu();
        }
        if (PLATFORM_QQBOT.equals(platform)) {
            return qqbot();
        }
        return telegram();
    }

    public static ChannelConfigMeta telegram() {
        return new ChannelConfigMeta(
            PLATFORM_TELEGRAM,
            R.string.botdrop_platform_telegram,
            "https://t.me/BotDropSetupBot",
            R.string.botdrop_bot_token,
            R.string.botdrop_telegram_token_hint,
            R.string.botdrop_owner_id,
            R.string.botdrop_telegram_owner_hint,
            R.string.botdrop_telegram_setup_help,
            true
        );
    }

    public static ChannelConfigMeta discord() {
        return new ChannelConfigMeta(
            PLATFORM_DISCORD,
            R.string.botdrop_platform_discord,
            "https://discord.com/developers/applications",
            R.string.botdrop_bot_token,
            R.string.botdrop_discord_token_hint,
            R.string.botdrop_owner_id,
            R.string.botdrop_discord_owner_id_hint,
            R.string.botdrop_discord_intro,
            false
        );
    }

    public static ChannelConfigMeta feishu() {
        return new ChannelConfigMeta(
            PLATFORM_FEISHU,
            R.string.botdrop_platform_feishu,
            "https://open.feishu.cn",
            R.string.botdrop_app_id,
            R.string.botdrop_feishu_app_id_hint,
            R.string.botdrop_app_secret,
            R.string.botdrop_feishu_app_secret_hint,
            R.string.botdrop_feishu_steps,
            true
        );
    }

    public static ChannelConfigMeta qqbot() {
        return new ChannelConfigMeta(
            PLATFORM_QQBOT,
            R.string.botdrop_platform_qqbot,
            "https://q.qq.com/",
            R.string.botdrop_app_id,
            R.string.botdrop_qqbot_app_id_hint,
            R.string.botdrop_app_secret,
            R.string.botdrop_qqbot_app_secret_hint,
            R.string.botdrop_qqbot_steps,
            true
        );
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        if (PLATFORM_TELEGRAM.equals(platform)) {
            return token.matches("^\\d+:[A-Za-z0-9_-]+$");
        }
        return true;
    }

    public boolean isOwnerValid(String ownerId) {
        if (!showOwnerField) {
            return true;
        }
        if (ownerId == null || ownerId.trim().isEmpty()) {
            return false;
        }
        if (PLATFORM_FEISHU.equals(platform)) {
            return true;
        }
        if (PLATFORM_QQBOT.equals(platform)) {
            return true;
        }
        return ownerId.matches("^\\d+$");
    }

    public boolean isDiscordGuildIdValid(String guildId) {
        if (!PLATFORM_DISCORD.equals(platform)) {
            return true;
        }
        if (guildId == null || guildId.trim().isEmpty()) {
            return false;
        }
        return guildId.matches("^\\d+$");
    }

    public boolean isDiscordChannelIdValid(String channelId) {
        if (!PLATFORM_DISCORD.equals(platform)) {
            return true;
        }
        if (channelId == null || channelId.trim().isEmpty()) {
            return false;
        }
        return channelId.matches("^\\d+$");
    }
}
