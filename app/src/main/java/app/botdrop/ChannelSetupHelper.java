package app.botdrop;

import android.util.Base64;

import android.text.TextUtils;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Helper for channel setup:
 * - Decode setup codes from @BotDropSetupBot
 * - Write channel configuration to openclaw.json
 */
public class ChannelSetupHelper {

    private static final String LOG_TAG = "ChannelSetupHelper";

    /**
     * Data extracted from setup code
     */
    public static class SetupCodeData {
        public final String platform;
        public final String botToken;
        public final String ownerId;

        public SetupCodeData(String platform, String botToken, String ownerId) {
            this.platform = platform;
            this.botToken = botToken;
            this.ownerId = ownerId;
        }
    }

    /**
     * Decode setup code from @BotDropSetupBot
     * Format: BOTDROP-{platform}-{base64_json}
     * 
     * Platform codes:
     * - tg = Telegram
     * - dc = Discord
     * - fs = Feishu
     * 
     * Base64 JSON structure:
     * {
     *   "v": 1,
     *   "platform": "telegram" | "discord" | "feishu",
     *   "bot_token": "...",
     *   "owner_id": "...",
     *   "created_at": 1234567890
     * }
     * 
     * @param setupCode The setup code from @BotDropSetupBot
     * @return SetupCodeData or null if invalid
     */
    public static SetupCodeData decodeSetupCode(String setupCode) {
        try {
            // Split: BOTDROP-tg-xxxxx or BOTDROP-dc-xxxxx
            String[] parts = setupCode.split("-", 3);
            if (parts.length != 3 || !parts[0].equals("BOTDROP")) {
                Logger.logError(LOG_TAG, "Invalid setup code format");
                return null;
            }

            String platformCode = parts[1];
            String base64Payload = parts[2];

            // Decode base64
            byte[] decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT);
            String jsonString = new String(decodedBytes);

            // NOTE: Do not log jsonString - it contains sensitive bot_token
            Logger.logDebug(LOG_TAG, "Setup code decoded successfully");

            // Parse JSON
            JSONObject json = new JSONObject(jsonString);

            String platform = json.optString("platform", null);
            String botToken = json.optString("bot_token", null);
            String ownerId = json.optString("owner_id", null);

            // Fallback: infer platform from code if not in JSON
            if (platform == null) {
                platform = platformCode.equals("tg") ? "telegram" :
                          platformCode.equals("dc") ? "discord" :
                          platformCode.equals("fs") ? "feishu" : null;
            }

            if (platform == null || botToken == null || ownerId == null) {
                Logger.logError(LOG_TAG, "Missing required fields in setup code");
                return null;
            }

            Logger.logInfo(LOG_TAG, "Setup code decoded: platform=" + platform + ", ownerId=" + ownerId);
            return new SetupCodeData(platform, botToken, ownerId);

        } catch (IllegalArgumentException | JSONException e) {
            Logger.logError(LOG_TAG, "Failed to decode setup code: " + e.getMessage());
            return null;
        }
    }

    // ── Channel detection helpers ──────────────────────────────────────

    /**
     * Check if any channel (Telegram, Discord, Feishu, or QQ Bot) is configured.
     * Reads openclaw.json and checks all supported platforms.
     */
    public static boolean hasAnyChannelConfigured() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null) {
                return false;
            }
            return isTelegramConfigured(channels.optJSONObject("telegram"))
                || isDiscordConfigured(channels.optJSONObject("discord"))
                || isFeishuConfigured(channels.optJSONObject("feishu"))
                || isQQBotConfigured(channels.optJSONObject("qqbot"));
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check channel config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a channel configuration from openclaw.json.
     *
     * @param platform "telegram" | "discord" | "feishu" | "qqbot"
     * @return true if config was removed and file write succeeded
     */
    public static boolean removeChannelConfig(String platform) {
        if (TextUtils.isEmpty(platform)) {
            Logger.logError(LOG_TAG, "Platform is empty");
            return false;
        }
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            JSONObject plugins = config != null ? config.optJSONObject("plugins") : null;
            boolean removed = false;

            if (channels != null && channels.has(platform)) {
                channels.remove(platform);
                removed = true;
                if (channels.length() == 0) {
                    config.remove("channels");
                }
            }

            if (plugins != null) {
                JSONObject entries = plugins.optJSONObject("entries");
                if (entries != null && entries.has(platform)) {
                    entries.remove(platform);
                    removed = true;
                    if (entries.length() == 0) {
                        plugins.remove("entries");
                    }
                }
                if (plugins.length() == 0) {
                    config.remove("plugins");
                }
            }

            if (!removed) {
                return false;
            }

            Logger.logInfo(LOG_TAG, "Removing channel config for platform: " + platform);
            return BotDropConfig.writeConfig(config);

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to remove channel config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if Telegram channel is configured (has token + allowFrom or ownerId).
     */
    public static boolean isTelegramConfigured(JSONObject telegram) {
        if (telegram == null) {
            return false;
        }
        if (!telegram.optBoolean("enabled", true)) {
            return false;
        }
        if (TextUtils.isEmpty(telegram.optString("botToken", "").trim())) {
            return false;
        }
        JSONArray allowFrom = telegram.optJSONArray("allowFrom");
        if (allowFrom != null && allowFrom.length() > 0) {
            return true;
        }
        Object ownerId = telegram.opt("ownerId");
        return ownerId != null && !TextUtils.isEmpty(String.valueOf(ownerId).trim());
    }

    /**
     * Check if Discord channel is configured (has token + at least one guild).
     */
    public static boolean isDiscordConfigured(JSONObject discord) {
        if (discord == null) {
            return false;
        }
        if (!discord.optBoolean("enabled", true)) {
            return false;
        }
        if (TextUtils.isEmpty(discord.optString("token", "").trim())) {
            return false;
        }
        JSONObject guilds = discord.optJSONObject("guilds");
        if (guilds == null || guilds.length() == 0) {
            return false;
        }
        Iterator<String> guildIterator = guilds.keys();
        while (guildIterator.hasNext()) {
            String guildId = guildIterator.next();
            if (!TextUtils.isEmpty(guildId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if Feishu channel is configured (has appId + appSecret + valid dmPolicy).
     */
    public static boolean isFeishuConfigured(JSONObject feishu) {
        if (feishu == null) {
            return false;
        }
        if (!feishu.optBoolean("enabled", true)) {
            return false;
        }
        JSONObject accounts = feishu.optJSONObject("accounts");
        JSONObject mainAccount = accounts != null ? accounts.optJSONObject("main") : null;
        if (mainAccount == null) {
            return false;
        }
        String appId = mainAccount.optString("appId", "").trim();
        String appSecret = mainAccount.optString("appSecret", "").trim();
        if (appId.isEmpty() || appSecret.isEmpty()) {
            return false;
        }
        String dmPolicy = feishu.optString("dmPolicy", "").trim();
        JSONArray allowFrom = feishu.optJSONArray("allowFrom");
        boolean allowlistReady = "allowlist".equals(dmPolicy) && allowFrom != null && allowFrom.length() > 0;
        boolean pairingReady = "pairing".equals(dmPolicy) || dmPolicy.isEmpty();
        return allowlistReady || pairingReady;
    }

    /**
     * Check if QQ Bot channel is configured (has appId + clientSecret).
     */
    public static boolean isQQBotConfigured(JSONObject qqbot) {
        if (qqbot == null) {
            return false;
        }
        if (!qqbot.optBoolean("enabled", true)) {
            return false;
        }
        String appId = qqbot.optString("appId", "").trim();
        String clientSecret = qqbot.optString("clientSecret", "").trim();
        return !appId.isEmpty() && !clientSecret.isEmpty();
    }

    // ── Write helpers ───────────────────────────────────────────────────

    /**
     * Write channel configuration to openclaw.json
     *
     * For Telegram: { channels: { telegram: { enabled: true, botToken: "...", dmPolicy: "allowlist" } } }
     * For Discord:  { channels: { discord: { enabled: true, token: "...", groupPolicy: "allowlist", guilds: { ... } } } }
     * If a guild is configured without a channel map (or with an empty one), it is interpreted as all channels in that guild.
     *
     * @param platform "telegram" or "discord"
     * @param botToken Bot token
     * @param ownerId User ID who owns/controls the bot (used for allowFrom)
     * @return true if successful
     */
    public static boolean writeChannelConfig(String platform, String botToken, String ownerId) {
        return writeChannelConfig(platform, botToken, ownerId, null, null);
    }

    /**
     * Write Feishu configuration to openclaw.json.
     *
     * For Feishu:
     * {
     *   channels: {
     *     feishu: {
     *       enabled: true,
     *       dmPolicy: "pairing", // default when user id is empty
     *       allowFrom: ["user-id"], // optional, required when dmPolicy is allowlist
     *       accounts: {
     *         main: { appId: "...", appSecret: "..." }
     *       }
     *     }
     *   }
     * }
     *
     * @param appId Feishu app ID
     * @param appSecret Feishu app secret
     * @param userId Feishu user open_id. Optional. If provided, dmPolicy is allowlist.
     * @return true if successful
     */
    public static boolean writeFeishuChannelConfig(String appId, String appSecret, String userId) {
        try {
            JSONObject config = BotDropConfig.readConfig();

            if (!config.has("channels")) {
                config.put("channels", new JSONObject());
            }

            JSONObject channels = config.getJSONObject("channels");
            JSONObject feishu = new JSONObject();
            feishu.put("enabled", true);
            boolean hasUserId = !TextUtils.isEmpty(userId);
            feishu.put("dmPolicy", hasUserId ? "allowlist" : "pairing");
            if (!TextUtils.isEmpty(userId)) {
                JSONArray allowFrom = new JSONArray();
                allowFrom.put(userId);
                feishu.put("allowFrom", allowFrom);
            }

            JSONObject accounts = new JSONObject();
            JSONObject mainAccount = new JSONObject();
            mainAccount.put("appId", appId);
            mainAccount.put("appSecret", appSecret);
            accounts.put("main", mainAccount);
            feishu.put("accounts", accounts);
            channels.put("feishu", feishu);

            if (!config.has("plugins")) {
                config.put("plugins", new JSONObject());
            }
            JSONObject plugins = config.getJSONObject("plugins");
            if (!plugins.has("entries")) {
                plugins.put("entries", new JSONObject());
            }
            JSONObject entries = plugins.getJSONObject("entries");
            JSONObject pluginEntry = new JSONObject();
            pluginEntry.put("enabled", true);
            entries.put("feishu", pluginEntry);

            Logger.logInfo(LOG_TAG, "Writing channel config for platform: feishu");
            return BotDropConfig.writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write channel config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write QQ Bot configuration to openclaw.json.
     *
     * For QQ Bot:
     * {
     *   channels: {
     *     qqbot: {
     *       enabled: true,
     *       appId: "...",
     *       clientSecret: "..."
     *     }
     *   }
     * }
     *
     * @param appId QQ Bot app ID
     * @param clientSecret QQ Bot app secret
     * @return true if successful
     */
    public static boolean writeQQBotChannelConfig(String appId, String clientSecret) {
        try {
            JSONObject config = BotDropConfig.readConfig();

            if (!config.has("channels")) {
                config.put("channels", new JSONObject());
            }

            JSONObject channels = config.getJSONObject("channels");
            JSONObject qqbot = new JSONObject();
            qqbot.put("enabled", true);
            qqbot.put("appId", appId);
            qqbot.put("clientSecret", clientSecret);
            channels.put("qqbot", qqbot);

            if (!config.has("plugins")) {
                config.put("plugins", new JSONObject());
            }
            JSONObject plugins = config.getJSONObject("plugins");
            if (!plugins.has("entries")) {
                plugins.put("entries", new JSONObject());
            }
            JSONObject entries = plugins.getJSONObject("entries");
            JSONObject pluginEntry = new JSONObject();
            pluginEntry.put("enabled", true);
            entries.put("qqbot", pluginEntry);

            Logger.logInfo(LOG_TAG, "Writing channel config for platform: qqbot");
            return BotDropConfig.writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write channel config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write channel configuration to openclaw.json
     *
     * For Telegram: { channels: { telegram: { enabled: true, botToken: "...", dmPolicy: "allowlist" } } }
     * For Discord:  { channels: { discord: { enabled: true, token: "...", groupPolicy: "allowlist", guilds: { ... } } } }
     * If a guild is configured without a channel map (or with an empty one), it is interpreted as all channels in that guild.
     *
     * @param platform "telegram" or "discord"
     * @param botToken Bot token
     * @param ownerId User ID who owns/controls the bot (used for allowFrom)
     * @param guildId Discord guild ID (optional for non-discord platforms)
     * @param channelId Discord channel ID (optional; when omitted, Discord config applies to all channels in the guild)
     * @return true if successful
     */
    public static boolean writeChannelConfig(
        String platform,
        String botToken,
        String ownerId,
        String guildId,
        String channelId
    ) {
        try {
            JSONObject config = BotDropConfig.readConfig();

            if (!config.has("channels")) {
                config.put("channels", new JSONObject());
            }

            JSONObject channels = config.getJSONObject("channels");

            if (platform.equals("telegram")) {
                JSONObject telegram = new JSONObject();
                telegram.put("enabled", true);
                telegram.put("botToken", botToken);
                telegram.put("dmPolicy", "allowlist");
                telegram.put("groupPolicy", "allowlist");
                telegram.put("streamMode", "partial");
                JSONArray allowFrom = new JSONArray();
                allowFrom.put(ownerId);
                telegram.put("allowFrom", allowFrom);
                channels.put("telegram", telegram);

            } else if (platform.equals("discord")) {
                JSONObject existingDiscord = channels.optJSONObject("discord");
                JSONObject discord = buildDiscordConfig(existingDiscord, botToken, guildId, channelId);
                channels.put("discord", discord);

            } else {
                Logger.logError(LOG_TAG, "Unsupported platform: " + platform);
                return false;
            }

            // Enable channel plugin
            if (!config.has("plugins")) {
                config.put("plugins", new JSONObject());
            }
            JSONObject plugins = config.getJSONObject("plugins");
            if (!plugins.has("entries")) {
                plugins.put("entries", new JSONObject());
            }
            JSONObject entries = plugins.getJSONObject("entries");
            JSONObject pluginEntry = new JSONObject();
            pluginEntry.put("enabled", true);
            entries.put(platform, pluginEntry);

            Logger.logInfo(LOG_TAG, "Writing channel config for platform: " + platform);
            return BotDropConfig.writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write channel config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Build a Discord channel config block from current config and inputs.
     *
     * This keeps existing guild/channel mappings when no explicit channelId is provided.
     * When a new guild is introduced without channelId, guild.channels defaults to empty object.
     */
    static JSONObject buildDiscordConfig(
        JSONObject existingDiscord,
        String botToken,
        String guildId,
        String channelId
    ) throws JSONException {
        JSONObject discord = existingDiscord != null ? new JSONObject(existingDiscord.toString()) : new JSONObject();
        discord.put("enabled", true);
        discord.put("token", botToken);
        discord.put("groupPolicy", "allowlist");

        if (TextUtils.isEmpty(guildId)) {
            return discord;
        }

        JSONObject existingGuilds = existingDiscord != null ? existingDiscord.optJSONObject("guilds") : null;
        JSONObject guilds = existingGuilds != null ? new JSONObject(existingGuilds.toString()) : new JSONObject();
        JSONObject existingGuild = existingGuilds != null ? existingGuilds.optJSONObject(guildId) : null;
        JSONObject guild = existingGuild != null ? new JSONObject(existingGuild.toString()) : new JSONObject();
        JSONObject guildChannels = guild.optJSONObject("channels");
        if (guildChannels == null) {
            guildChannels = new JSONObject();
        }

        if (!TextUtils.isEmpty(channelId)) {
            JSONObject channel = new JSONObject();
            channel.put("allow", true);
            channel.put("requireMention", false);
            channel.put("autoThread", false);
            guildChannels.put(channelId, channel);
        }

        guild.put("channels", guildChannels);
        guilds.put(guildId, guild);
        discord.put("guilds", guilds);
        return discord;
    }
}
