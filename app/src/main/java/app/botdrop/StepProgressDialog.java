package app.botdrop;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StepProgressDialog {

    private static final int MAX_STEPS = 5;
    private static final String ICON_DONE = "\u2713";
    private static final String ICON_CURRENT = "\u25CF";
    private static final String ICON_PENDING = "\u25CB";

    private final Context mContext;
    private final AlertDialog mDialog;
    private final TextView mStatusText;
    private final Button mCloseButton;
    private final TextView[] mStepIcons = new TextView[MAX_STEPS];
    private final int mStepCount;
    private int mCurrentStep = -1;

    private StepProgressDialog(
        @NonNull Context context,
        @StringRes int titleRes,
        @NonNull List<String> stepLabels,
        @Nullable String initialStatus
    ) {
        mContext = context;
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_openclaw_update, null);
        TextView[] stepTexts = {
            dialogView.findViewById(R.id.update_step_0_text),
            dialogView.findViewById(R.id.update_step_1_text),
            dialogView.findViewById(R.id.update_step_2_text),
            dialogView.findViewById(R.id.update_step_3_text),
            dialogView.findViewById(R.id.update_step_4_text),
        };
        mStepIcons[0] = dialogView.findViewById(R.id.update_step_0_icon);
        mStepIcons[1] = dialogView.findViewById(R.id.update_step_1_icon);
        mStepIcons[2] = dialogView.findViewById(R.id.update_step_2_icon);
        mStepIcons[3] = dialogView.findViewById(R.id.update_step_3_icon);
        mStepIcons[4] = dialogView.findViewById(R.id.update_step_4_icon);

        mStepCount = Math.min(MAX_STEPS, stepLabels.size());
        for (int i = 0; i < MAX_STEPS; i++) {
            TextView iconText = stepTexts[i];
            TextView icon = mStepIcons[i];
            if (iconText == null || icon == null) {
                continue;
            }
            if (i >= mStepCount) {
                iconText.setVisibility(View.GONE);
                icon.setVisibility(View.GONE);
            } else {
                iconText.setVisibility(View.VISIBLE);
                icon.setVisibility(View.VISIBLE);
                iconText.setText(stepLabels.get(i));
                icon.setText(ICON_PENDING);
            }
        }

        mStatusText = dialogView.findViewById(R.id.update_status_message);
        if (mStatusText != null) {
            mStatusText.setText(TextUtils.isEmpty(initialStatus)
                    ? context.getString(R.string.botdrop_may_take_a_few_minutes)
                    : initialStatus);
        }

        mCloseButton = dialogView.findViewById(R.id.update_error_close_button);
        if (mCloseButton != null) {
            mCloseButton.setVisibility(View.GONE);
        }

        mDialog = new AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(dialogView)
            .setCancelable(false)
            .create();
    }

    @NonNull
    public static StepProgressDialog create(
        @NonNull Context context,
        @StringRes int titleRes,
        @NonNull List<String> stepLabels,
        @Nullable String initialStatus
    ) {
        return new StepProgressDialog(context, titleRes, Collections.unmodifiableList(new ArrayList<>(stepLabels)), initialStatus);
    }

    public void show() {
        mDialog.show();
    }

    public boolean isShowing() {
        return mDialog.isShowing();
    }

    public void dismiss() {
        mDialog.dismiss();
    }

    public void setStatus(@Nullable String message) {
        if (mStatusText != null) {
            mStatusText.setText(TextUtils.isEmpty(message)
                    ? mContext.getString(R.string.botdrop_may_take_a_few_minutes)
                    : message);
        }
    }

    public void setStatus(@StringRes int messageRes) {
        setStatus(mContext.getString(messageRes));
    }

    public void setStep(int nextStep) {
        if (nextStep < 0 || nextStep >= mStepCount) {
            return;
        }
        if (nextStep <= mCurrentStep) {
            return;
        }
        for (int i = 0; i <= nextStep && i < mStepCount; i++) {
            if (mStepIcons[i] != null) {
                mStepIcons[i].setText(ICON_DONE);
            }
        }
        if (mStepIcons[nextStep] != null) {
            mStepIcons[nextStep].setText(ICON_CURRENT);
        }
        mCurrentStep = nextStep;
    }

    public void complete(@Nullable String completionMessage) {
        for (int i = 0; i < mStepCount; i++) {
            if (mStepIcons[i] != null) {
                mStepIcons[i].setText(ICON_DONE);
            }
        }
        if (!TextUtils.isEmpty(completionMessage)) {
            setStatus(completionMessage);
        }
        if (mStepCount > 0) {
            mCurrentStep = mStepCount - 1;
        }
    }

    public void showError(@Nullable String errorMessage, @Nullable Runnable onClose) {
        setStatus(errorMessage);
        if (mCloseButton != null) {
            mCloseButton.setText(mContext.getString(R.string.botdrop_close));
            mCloseButton.setOnClickListener(v -> {
                if (onClose != null) {
                    onClose.run();
                }
                dismiss();
            });
            mCloseButton.setVisibility(View.VISIBLE);
        } else {
            mDialog.setButton(AlertDialog.BUTTON_NEGATIVE, mContext.getString(R.string.botdrop_close),
                (dialog, which) -> {
                    if (onClose != null) {
                        onClose.run();
                    }
                    dialog.dismiss();
                });
        }
    }
}
