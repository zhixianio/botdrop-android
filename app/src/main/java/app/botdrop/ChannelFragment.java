package app.botdrop;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.shared.logger.Logger;

/**
 * Step 3 of setup: Channel setup with multiple IM platform tabs.
 */
public class ChannelFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFragment";
    // Short label to keep the scrollable tab bar compact (full name "QQ Bot" / "QQ 机器人" is too wide).
    private static final String QQ_TAB_TITLE = "QQ";
    private static final int[] TAB_TITLE_IDS = {
        R.string.botdrop_platform_telegram,
        R.string.botdrop_platform_discord,
        R.string.botdrop_platform_feishu,
        R.string.botdrop_platform_qqbot
    };

    private TabLayout mChannelTabs;
    private ViewPager2 mChannelPager;
    private ChannelPagerAdapter mPagerAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_botdrop_channel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mChannelTabs = view.findViewById(R.id.channel_tabs);
        mChannelPager = view.findViewById(R.id.channel_viewpager);

        if (mChannelTabs == null || mChannelPager == null) {
            Logger.logError(LOG_TAG, "Channel tab views are missing from layout");
            return;
        }

        mPagerAdapter = new ChannelPagerAdapter(this);
        mChannelPager.setAdapter(mPagerAdapter);
        // Keep only adjacent page cached to avoid all tabs starting their service bindings at once.
        mChannelPager.setOffscreenPageLimit(1);

        new TabLayoutMediator(mChannelTabs, mChannelPager, (tab, position) -> {
            tab.setText(resolveTabTitle(position));
        }).attach();

        String platform = null;
        if (getActivity() != null) {
            platform = getActivity().getIntent().getStringExtra(SetupActivity.EXTRA_CHANNEL_PLATFORM);
        }
        int defaultTab = resolveTabIndex(platform);
        mChannelPager.setCurrentItem(defaultTab, false);
        mChannelPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (getContext() != null) {
                    AnalyticsManager.logEvent(getContext(), "channel_tab_view", "platform", getAnalyticsPlatform(position));
                }
            }
        });
        if (getContext() != null) {
            AnalyticsManager.logEvent(getContext(), "channel_tab_view", "platform", getAnalyticsPlatform(defaultTab));
        }
    }

    private int resolveTabIndex(String platform) {
        if (ChannelConfigMeta.PLATFORM_DISCORD.equals(platform)) {
            return ChannelPagerAdapter.PAGE_DISCORD;
        }
        if (ChannelConfigMeta.PLATFORM_FEISHU.equals(platform)) {
            return ChannelPagerAdapter.PAGE_FEISHU;
        }
        if (ChannelConfigMeta.PLATFORM_QQBOT.equals(platform)) {
            return ChannelPagerAdapter.PAGE_QQBOT;
        }
        return ChannelPagerAdapter.PAGE_TELEGRAM;
    }

    private String getAnalyticsPlatform(int position) {
        switch (position) {
            case ChannelPagerAdapter.PAGE_DISCORD:
                return ChannelConfigMeta.PLATFORM_DISCORD;
            case ChannelPagerAdapter.PAGE_FEISHU:
                return ChannelConfigMeta.PLATFORM_FEISHU;
            case ChannelPagerAdapter.PAGE_QQBOT:
                return ChannelConfigMeta.PLATFORM_QQBOT;
            default:
                return ChannelConfigMeta.PLATFORM_TELEGRAM;
        }
    }

    private String resolveTabTitle(int position) {
        if (position == ChannelPagerAdapter.PAGE_QQBOT) {
            return QQ_TAB_TITLE;
        }

        if (position >= 0 && position < TAB_TITLE_IDS.length) {
            return getString(TAB_TITLE_IDS[position]);
        }
        return String.valueOf(position + 1);
    }
}
