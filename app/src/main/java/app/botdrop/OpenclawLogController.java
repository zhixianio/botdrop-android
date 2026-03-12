package app.botdrop;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

public final class OpenclawLogController {

    private static final int GATEWAY_LOG_TAIL_LINES = 300;
    private static final int GATEWAY_DEBUG_LOG_TAIL_LINES = 120;
    private static final int NODE_LOG_TAIL_LINES = 300;
    private static final long OPENCLAW_LOG_TAIL_POLL_INTERVAL_MS = 2500L;
    private static final String GATEWAY_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.log";
    private static final String GATEWAY_DEBUG_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway-debug.log";
    private static final String NODE_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw-node/node.log";
    private static final String GATEWAY_LOG_LABEL = "gateway.log";
    private static final String GATEWAY_DEBUG_LOG_LABEL = "gateway-debug.log";
    private static final String NODE_LOG_LABEL = "node.log";

    private final DashboardActivity mActivity;
    private final android.os.Handler mHandler;
    private TextView mOpenclawLogButton;

    public OpenclawLogController(@NonNull DashboardActivity activity, @NonNull android.os.Handler handler,
                                 @Nullable TextView openclawLogButton) {
        mActivity = activity;
        mHandler = handler;
        mOpenclawLogButton = openclawLogButton;
    }

    public void setOpenclawLogButton(@Nullable TextView openclawLogButton) {
        mOpenclawLogButton = openclawLogButton;
    }

    public void showOpenclawLog() {
        showOpenclawLog(false);
    }

    public void showOpenclawLog(boolean nodeMode) {
        BotDropService service = mActivity.getCurrentBotDropService();
        if (service == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_service_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setEnabled(false);
        }

        View logDialogView = mActivity.getLayoutInflater().inflate(R.layout.dialog_openclaw_log, null);
        TextView logTitle = logDialogView.findViewById(R.id.openclaw_log_title);
        TabLayout logTabLayout = logDialogView.findViewById(R.id.openclaw_log_tabs);
        ViewPager2 logViewPager = logDialogView.findViewById(R.id.openclaw_log_viewpager);
        Button copyButton = logDialogView.findViewById(R.id.openclaw_log_copy_button);
        Button closeButton = logDialogView.findViewById(R.id.openclaw_log_close_button);

        if (logTabLayout == null || logViewPager == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_failed_to_read_openclaw_logs), Toast.LENGTH_SHORT).show();
            if (mOpenclawLogButton != null) {
                mOpenclawLogButton.setEnabled(true);
            }
            return;
        }

        final String[] logFiles = nodeMode
            ? new String[]{NODE_LOG_FILE}
            : new String[]{GATEWAY_LOG_FILE, GATEWAY_DEBUG_LOG_FILE};
        final int[] logTailLines = nodeMode
            ? new int[]{NODE_LOG_TAIL_LINES}
            : new int[]{GATEWAY_LOG_TAIL_LINES, GATEWAY_DEBUG_LOG_TAIL_LINES};
        final String[] logLabels = nodeMode
            ? new String[]{NODE_LOG_LABEL}
            : new String[]{GATEWAY_LOG_LABEL, GATEWAY_DEBUG_LOG_LABEL};
        final String[] logContents = new String[logLabels.length];
        for (int i = 0; i < logLabels.length; i++) {
            logContents[i] = "Loading " + logLabels[i] + "...";
        }
        final String noLogOutputText = mActivity.getString(R.string.botdrop_no_log_output_available);
        if (logTitle != null) {
            logTitle.setText(nodeMode
                ? mActivity.getString(R.string.botdrop_openclaw_node_log)
                : mActivity.getString(R.string.botdrop_openclaw_gateway_log)
            );
        }

        RecyclerView.Adapter<OpenclawLogPageViewHolder> pagerAdapter = new RecyclerView.Adapter<OpenclawLogPageViewHolder>() {
            @NonNull
            @Override
            public OpenclawLogPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_openclaw_log_page, parent, false);
                return new OpenclawLogPageViewHolder(itemView);
            }

            @Override
            public void onBindViewHolder(@NonNull OpenclawLogPageViewHolder holder, int position) {
                String text = logContents[position];
                if (TextUtils.isEmpty(text)) {
                    text = noLogOutputText;
                }
                holder.bind(text);
            }

            @Override
            public int getItemCount() {
                return logContents.length;
            }
        };

        logViewPager.setAdapter(pagerAdapter);
        logViewPager.setOffscreenPageLimit(1);
        if (logLabels.length <= 1) {
            logTabLayout.setVisibility(View.GONE);
        } else {
            logTabLayout.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setView(logDialogView)
            .create();

        final boolean[] isLogDialogOpen = {true};
        final int[] pollBatchPending = {0};
        final int[] remainingFirstLoadCallbacks = {logLabels.length};
        Runnable refreshLogs = new Runnable() {
            @Override
            public void run() {
                if (!isLogDialogOpen[0] || service == null) {
                    return;
                }

                pollBatchPending[0] = logLabels.length;

                Runnable onPollCompleted = () -> {
                    int remaining = --pollBatchPending[0];
                    if (remainingFirstLoadCallbacks[0] > 0) {
                        int firstLoadRemaining = --remainingFirstLoadCallbacks[0];
                        if (firstLoadRemaining <= 0 && mOpenclawLogButton != null) {
                            mOpenclawLogButton.setEnabled(true);
                        }
                    }
                    if (remaining <= 0) {
                        if (isLogDialogOpen[0] && !mActivity.isFinishing()) {
                            mHandler.postDelayed(this, OPENCLAW_LOG_TAIL_POLL_INTERVAL_MS);
                        }
                    }
                };

                for (int i = 0; i < logLabels.length; i++) {
                    int index = i;
                    service.executeCommand(
                        getOpenclawLogTailCommand(logFiles[index], logTailLines[index]),
                        result -> {
                            if (mActivity.isFinishing() || !isLogDialogOpen[0]) {
                                onPollCompleted.run();
                                return;
                            }
                            String logText = getFormattedLogResult(result, logLabels[index]);
                            logContents[index] = logText;
                            pagerAdapter.notifyItemChanged(index);
                            onPollCompleted.run();
                        }
                    );
                }
            }
        };

        refreshLogs.run();

        if (logLabels.length > 1) {
            new com.google.android.material.tabs.TabLayoutMediator(logTabLayout, logViewPager, (tab, position) ->
                tab.setText(logLabels[position])
            ).attach();
        }

        copyButton.setOnClickListener(v -> {
            int currentItem = logViewPager.getCurrentItem();
            String copyTarget = noLogOutputText;
            String copyLabel = nodeMode ? mActivity.getString(R.string.botdrop_openclaw_node_log)
                : mActivity.getString(R.string.botdrop_openclaw_gateway_log);
            if (currentItem >= 0 && currentItem < logContents.length) {
                copyTarget = logContents[currentItem];
                copyLabel = logLabels[currentItem];
            }
            if (TextUtils.isEmpty(copyTarget)) {
                copyTarget = noLogOutputText;
            }
            copyToClipboard(copyLabel, copyTarget);
        });

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            isLogDialogOpen[0] = false;
            mHandler.removeCallbacks(refreshLogs);
            if (mOpenclawLogButton != null) {
                mOpenclawLogButton.setEnabled(true);
            }
        });

        dialog.show();
    }

    private static String getOpenclawLogTailCommand(String logFile, int tailLines) {
        return "if [ -f " + logFile + " ]; then\n" +
                "  tail -n " + tailLines + " " + logFile + "\n" +
                "else\n" +
                "  echo 'No log file at " + logFile + "'\n" +
                "fi\n";
    }

    private void copyToClipboard(String label, String content) {
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_clipboard_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        String textToCopy = content == null ? "" : content;
        ClipData clip = ClipData.newPlainText(label, textToCopy);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(mActivity, mActivity.getString(R.string.botdrop_log_copied), Toast.LENGTH_SHORT).show();
    }

    private String getFormattedLogResult(@Nullable BotDropService.CommandResult result, String logLabel) {
        String logText = null;
        if (result == null) {
            logText = mActivity.getString(R.string.botdrop_failed_to_read_openclaw_logs);
        }
        if (logText == null) {
            if (!result.success) {
                StringBuilder fallback = new StringBuilder();
                if (!TextUtils.isEmpty(result.stderr)) {
                    fallback.append(result.stderr.trim());
                }
                if (!TextUtils.isEmpty(result.stdout)) {
                    if (fallback.length() > 0) {
                        fallback.append("\n\n");
                    }
                    fallback.append(result.stdout.trim());
                }
                logText = fallback.toString();
                if (TextUtils.isEmpty(logText)) {
                    logText = mActivity.getString(R.string.botdrop_failed_to_read_openclaw_logs_exit_code, result.exitCode);
                }
            } else {
                logText = result.stdout;
            }
            if (TextUtils.isEmpty(logText)) {
                logText = mActivity.getString(R.string.botdrop_failed_to_read_openclaw_logs_exit_code, result.exitCode);
            }
        }

        if (TextUtils.isEmpty(logText)) {
            logText = mActivity.getString(R.string.botdrop_no_log_output_available);
        }
        return logLabel + "\n" + logText;
    }

    private static class OpenclawLogPageViewHolder extends RecyclerView.ViewHolder {
        private final TextView mLogTextView;

        OpenclawLogPageViewHolder(@NonNull View itemView) {
            super(itemView);
            mLogTextView = itemView.findViewById(R.id.openclaw_log_page_text);
            if (mLogTextView != null) {
                mLogTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
            }
        }

        void bind(String text) {
            if (mLogTextView != null) {
                mLogTextView.setText(text == null ? "" : text);
            }
        }
    }
}
