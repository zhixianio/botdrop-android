package app.botdrop;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.termux.R;

public final class BotDropDialogStyler {

    private BotDropDialogStyler() {}

    @NonNull
    public static AlertDialog.Builder createBuilder(@NonNull Context context) {
        return new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.TermuxAlertDialogStyle));
    }

    @NonNull
    public static LinearLayout createInlineProgressContent(@NonNull Context context, @StringRes int messageRes) {
        float density = context.getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        int spacing = (int) (16 * density);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundResource(R.drawable.botdrop_dialog_card_bg);

        ProgressBar progressBar = new ProgressBar(context);
        progressBar.setId(android.R.id.progress);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.botdrop_accent)
            ));
        }
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        progressParams.rightMargin = spacing;
        content.addView(progressBar, progressParams);

        TextView messageView = new TextView(context);
        messageView.setId(android.R.id.message);
        messageView.setText(messageRes);
        messageView.setTextSize(16f);
        messageView.setTextColor(ContextCompat.getColor(context, R.color.botdrop_on_background));
        messageView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        content.addView(messageView, messageParams);

        return content;
    }

    public static void applyTransparentCardWindow(Dialog dialog) {
        if (dialog == null) {
            return;
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }
}
