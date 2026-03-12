package app.botdrop;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class ChannelConfigMetaTest {

    @Test
    public void testTelegramValidation() {
        ChannelConfigMeta meta = ChannelConfigMeta.telegram();
        assertTrue(meta.isTokenValid("123456789:ABC-abc_123"));
        assertFalse(meta.isTokenValid("bad-token"));
        assertTrue(meta.isOwnerValid("123456"));
        assertFalse(meta.isOwnerValid("abc"));
        assertFalse(meta.isOwnerValid(""));
    }

    @Test
    public void testDiscordValidation() {
        ChannelConfigMeta meta = ChannelConfigMeta.discord();
        assertTrue(meta.isTokenValid("any-discord-token"));
        assertTrue(meta.isOwnerValid("")); // owner optional for discord
    }

    @Test
    public void testFeishuValidation() {
        ChannelConfigMeta meta = ChannelConfigMeta.feishu();
        assertTrue(meta.isTokenValid("any-feishu-token"));
        assertFalse(meta.isTokenValid(""));
        assertFalse(meta.isOwnerValid(""));
        assertTrue(meta.isOwnerValid("feishu-app-secret"));
        assertTrue(meta.isDiscordGuildIdValid(""));  // not discord, always true
        assertTrue(meta.isDiscordChannelIdValid("")); // not discord, always true
    }

    @Test
    public void testQQBotValidation() {
        ChannelConfigMeta meta = ChannelConfigMeta.qqbot();
        assertTrue(meta.isTokenValid("qq-app-id"));
        assertFalse(meta.isTokenValid(""));
        assertFalse(meta.isOwnerValid(""));
        assertTrue(meta.isOwnerValid("qq-app-secret"));
        assertTrue(meta.isDiscordGuildIdValid(""));
        assertTrue(meta.isDiscordChannelIdValid(""));
    }
}
