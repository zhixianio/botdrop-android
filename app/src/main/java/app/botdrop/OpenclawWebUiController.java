package app.botdrop;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.net.HttpURLConnection;
import java.net.URL;

public final class OpenclawWebUiController {

    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT = 8;
    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS = 700;

    private final DashboardActivity mActivity;
    private final Handler mHandler;
    private TextView mOpenclawWebUiButton;
    private boolean mOpenclawWebUiOpening;
    private Runnable mPendingWebUiOpenRunnable;

    public OpenclawWebUiController(@NonNull DashboardActivity activity,
                                  @NonNull Handler handler,
                                  @Nullable TextView webUiButton) {
        mActivity = activity;
        mHandler = handler;
        mOpenclawWebUiButton = webUiButton;
    }

    public void setOpenclawWebUiButton(@Nullable TextView webUiButton) {
        mOpenclawWebUiButton = webUiButton;
    }

    public void onDestroy() {
        mOpenclawWebUiOpening = false;
        Runnable pending = mPendingWebUiOpenRunnable;
        if (pending != null) {
            mHandler.removeCallbacks(pending);
        }
        mPendingWebUiOpenRunnable = null;
    }

    public void applyRuntimeModeDependentState(@Nullable DashboardRuntimeModeSection section) {
        if (mOpenclawWebUiButton == null) {
            return;
        }

        if (section == null || !section.isOpenclawWebUiEnabled()) {
            mOpenclawWebUiOpening = false;
            mOpenclawWebUiButton.setVisibility(View.GONE);
        } else {
            mOpenclawWebUiButton.setVisibility(View.VISIBLE);
            setOpenclawWebUiButtonState(false, null);
        }
    }

    public void openOpenclawWebUi(
        @Nullable DashboardRuntimeModeSection section,
        boolean uiVisible,
        boolean isBound,
        @Nullable BotDropService service
    ) {
        if (!uiVisible) {
            return;
        }

        if (section == null || !section.isOpenclawWebUiEnabled()) {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_open_web_ui_not_available_in_node_mode),
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (!isBound || service == null) {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_service_not_connected),
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (mOpenclawWebUiOpening) {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_openclaw_web_ui_already_opening),
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        mOpenclawWebUiOpening = true;
        setOpenclawWebUiButtonState(true, mActivity.getString(R.string.botdrop_opening_web_ui));

        service.isGatewayRunning(result -> {
            if (!uiVisible) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                return;
            }

            if (result == null || !result.success || !"running".equals(result.stdout.trim())) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                Toast.makeText(
                    mActivity,
                    mActivity.getString(R.string.botdrop_openclaw_not_running),
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }

            resolveOpenclawWebUiUrl(url -> {
                if (!uiVisible) {
                    mOpenclawWebUiOpening = false;
                    setOpenclawWebUiButtonState(false, null);
                    return;
                }
                openOpenclawUrlWithReadinessCheck(url, 0, uiVisible);
            });
        });
    }

    private void openOpenclawUrlWithReadinessCheck(String webUiUrl, int attempt, boolean uiVisible) {
        if (!uiVisible) {
            mOpenclawWebUiOpening = false;
            setOpenclawWebUiButtonState(false, null);
            return;
        }

        final String url = TextUtils.isEmpty(webUiUrl)
            ? OpenclawWebUiConfigResolver.OPENCLAW_DEFAULT_WEB_UI_URL
            : webUiUrl.trim();
        final String resolvedUrl = TextUtils.isEmpty(url)
            ? OpenclawWebUiConfigResolver.OPENCLAW_DEFAULT_WEB_UI_URL
            : url;

        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (!uiVisible) {
                    mOpenclawWebUiOpening = false;
                    setOpenclawWebUiButtonState(false, null);
                    return;
                }

                final int nextAttempt = attempt;
                new Thread(() -> {
                    final boolean reachable = isOpenclawWebUiReachable(resolvedUrl);
                    if (reachable || nextAttempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                        mActivity.runOnUiThread(() -> {
                            mOpenclawWebUiOpening = false;
                            if (!mActivity.isFinishing()) {
                                if (!uiVisible) {
                                    return;
                                }
                                if (!reachable && nextAttempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                                    Toast.makeText(
                                        mActivity,
                                        mActivity.getString(R.string.botdrop_web_ui_still_starting),
                                        Toast.LENGTH_LONG
                                    ).show();
                                }
                                openOpenclawUrlInBrowser(resolvedUrl);
                            }
                        });
                        return;
                    }

                    int next = nextAttempt + 1;
                    mActivity.runOnUiThread(() -> setOpenclawWebUiButtonState(
                        true,
                        mActivity.getString(
                            R.string.botdrop_opening_web_ui_attempt,
                            next,
                            OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT
                        )
                    ));
                    mHandler.postDelayed(() -> openOpenclawUrlWithReadinessCheck(resolvedUrl, next, uiVisible),
                        OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS);
                }).start();
            }
        };

        mPendingWebUiOpenRunnable = poll;
        poll.run();
    }

    private void resolveOpenclawWebUiUrl(OpenclawUrlResolvedCallback callback) {
        if (callback == null) {
            return;
        }

        String configText = BotDropConfig.readConfig().toString();
        String gatewayToken = OpenclawWebUiConfigResolver.extractGatewayTokenFromConfig(configText);
        callback.onUrlResolved(OpenclawWebUiConfigResolver.resolveOpenclawWebUiUrl(configText, gatewayToken));
    }

    private boolean isOpenclawWebUiReachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            connection.disconnect();
            return code >= 200 && code < 600;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openOpenclawUrlInBrowser(String url) {
        String targetUrl = TextUtils.isEmpty(url) ? OpenclawWebUiConfigResolver.OPENCLAW_DEFAULT_WEB_UI_URL : url;

        try {
            Uri parsed = Uri.parse(targetUrl.trim());
            if (TextUtils.isEmpty(parsed.getScheme())
                || !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
                targetUrl = OpenclawWebUiConfigResolver.OPENCLAW_DEFAULT_WEB_UI_URL;
            }
        } catch (Exception ignored) {
            targetUrl = OpenclawWebUiConfigResolver.OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        Intent openUrlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
        try {
            mActivity.startActivity(openUrlIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_no_app_available_to_open_web_links),
                Toast.LENGTH_SHORT
            ).show();
        } catch (Exception e) {
            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.botdrop_open_browser_error),
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void setOpenclawWebUiButtonState(boolean opening, String statusText) {
        if (mOpenclawWebUiButton == null) {
            return;
        }

        if (mOpenclawWebUiButton.getVisibility() != View.VISIBLE) {
            return;
        }

        mOpenclawWebUiButton.setEnabled(!opening);
        mOpenclawWebUiButton.setAlpha(opening ? 0.6f : 1f);

        if (TextUtils.isEmpty(statusText)) {
            mOpenclawWebUiButton.setText(mActivity.getString(R.string.botdrop_open_web_ui));
        } else {
            mOpenclawWebUiButton.setText(statusText);
        }
    }

    public void onOpenclawWebUiAvailabilityChanged() {
        setOpenclawWebUiButtonState(false, null);
    }

    private interface OpenclawUrlResolvedCallback {
        void onUrlResolved(String url);
    }
}
