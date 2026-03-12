package app.botdrop;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.termux.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class DashboardUpdateBannerController {

    private final Activity mActivity;
    private final View mUpdateBanner;
    private final TextView mUpdateBannerText;

    public DashboardUpdateBannerController(@NonNull Activity activity,
                                          @Nullable View updateBanner,
                                          @Nullable TextView updateBannerText) {
        mActivity = activity;
        mUpdateBanner = updateBanner;
        mUpdateBannerText = updateBannerText;
    }

    public void show(@Nullable String latestVersion, @Nullable String downloadUrl) {
        if (mUpdateBanner == null || mUpdateBannerText == null) {
            return;
        }
        if (TextUtils.isEmpty(latestVersion) || TextUtils.isEmpty(downloadUrl)) {
            hide();
            return;
        }
        mUpdateBannerText.setText(mActivity.getString(R.string.botdrop_update_available_version, latestVersion));
        mUpdateBanner.setVisibility(View.VISIBLE);

        View downloadButton = mActivity.findViewById(R.id.btn_update_download);
        if (downloadButton != null) {
            downloadButton.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                mActivity.startActivity(browserIntent);
            });
        }

        View dismissButton = mActivity.findViewById(R.id.btn_update_dismiss);
        if (dismissButton != null) {
            dismissButton.setOnClickListener(v -> {
                mUpdateBanner.setVisibility(View.GONE);
                UpdateChecker.dismiss(mActivity, latestVersion);
            });
        }
    }

    public void hide() {
        if (mUpdateBanner != null) {
            mUpdateBanner.setVisibility(View.GONE);
        }
    }
}
