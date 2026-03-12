package app.botdrop;

import com.termux.R;

/**
 * QQ Bot configuration page in Channel tabs.
 */
public class QQBotChannelFragment extends ChannelFormFragment {

    @Override
    protected String getPlatformId() {
        return ChannelConfigMeta.PLATFORM_QQBOT;
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_botdrop_channel_qqbot;
    }
}
