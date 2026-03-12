package app.botdrop;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChannelFragmentLayoutTest {

    @Test
    public void imChannelPage_doesNotShowStepCounter() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class)
            .setup()
            .get();

        activity.setContentView(R.layout.fragment_botdrop_channel);
        View root = activity.findViewById(android.R.id.content);

        assertTrue(containsText(root, activity.getString(R.string.botdrop_connect_im_channels)));
        assertFalse(containsText(root, activity.getString(R.string.botdrop_step_3_4)));
    }

    private boolean containsText(View view, String expectedText) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (expectedText.contentEquals(text)) {
                return true;
            }
        }

        if (!(view instanceof ViewGroup)) {
            return false;
        }

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsText(group.getChildAt(i), expectedText)) {
                return true;
            }
        }
        return false;
    }
}
