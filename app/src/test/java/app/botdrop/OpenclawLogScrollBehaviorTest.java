package app.botdrop;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OpenclawLogScrollBehaviorTest {

    @Test
    public void bind_whenScrolledToBottom_keepsViewNearBottomAfterRefresh() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class)
            .setup()
            .get();

        View pageView = LayoutInflater.from(activity).inflate(R.layout.item_openclaw_log_page, null, false);
        activity.setContentView(pageView);
        ScrollView scrollView = (ScrollView) pageView;
        TextView textView = pageView.findViewById(R.id.openclaw_log_page_text);
        Object holder = createCurrentProductionHolder(pageView);

        bindWithCurrentProductionPath(holder, buildLogText(80));
        layoutPage(pageView, 1080, 320);
        ShadowLooper.idleMainLooper();

        int initialBottom = getBottomOffset(scrollView, textView);
        scrollView.scrollTo(0, initialBottom);
        ShadowLooper.idleMainLooper();

        bindWithCurrentProductionPath(holder, buildLogText(120));
        layoutPage(pageView, 1080, 320);
        ShadowLooper.idleMainLooper();
        ShadowLooper.idleMainLooper();

        int refreshedBottom = getBottomOffset(scrollView, textView);
        assertTrue(
            "Expected to remain anchored near the bottom after refresh, bottom=" + refreshedBottom + ", actual=" + scrollView.getScrollY(),
            refreshedBottom - scrollView.getScrollY() <= 10
        );
    }

    @Test
    public void bind_whenReadingMidLog_preservesScrollOffsetAfterRefresh() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class)
            .setup()
            .get();

        View pageView = LayoutInflater.from(activity).inflate(R.layout.item_openclaw_log_page, null, false);
        activity.setContentView(pageView);
        ScrollView scrollView = (ScrollView) pageView;
        TextView textView = pageView.findViewById(R.id.openclaw_log_page_text);
        Object holder = createCurrentProductionHolder(pageView);

        bindWithCurrentProductionPath(holder, buildLogText(120));
        layoutPage(pageView, 1080, 320);
        ShadowLooper.idleMainLooper();

        int expectedScrollY = Math.max(20, getBottomOffset(scrollView, textView) / 2);
        scrollView.scrollTo(0, expectedScrollY);
        ShadowLooper.idleMainLooper();

        bindWithCurrentProductionPath(holder, buildLogText(160));
        layoutPage(pageView, 1080, 320);
        ShadowLooper.idleMainLooper();
        ShadowLooper.idleMainLooper();

        assertEquals("Expected refresh to preserve the reader's current position", expectedScrollY, scrollView.getScrollY());
    }

    private Object createCurrentProductionHolder(View pageView) throws Exception {
        Class<?> holderClass = Class.forName("app.botdrop.DashboardActivity$OpenclawLogPageViewHolder");
        Constructor<?> constructor = holderClass.getDeclaredConstructor(View.class);
        constructor.setAccessible(true);
        return constructor.newInstance(pageView);
    }

    private void bindWithCurrentProductionPath(Object holder, String text) throws Exception {
        Class<?> holderClass = holder.getClass();
        Method bindMethod = holderClass.getDeclaredMethod("bind", String.class);
        bindMethod.setAccessible(true);
        bindMethod.invoke(holder, text);
    }

    private void layoutPage(View pageView, int width, int height) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        pageView.measure(widthSpec, heightSpec);
        pageView.layout(0, 0, width, height);

        View child = ((ScrollView) pageView).getChildAt(0);
        child.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        child.layout(0, 0, width, child.getMeasuredHeight());
    }

    private int getBottomOffset(ScrollView scrollView, TextView textView) {
        return Math.max(0, textView.getBottom() - scrollView.getHeight());
    }

    private String buildLogText(int lines) {
        StringBuilder builder = new StringBuilder("Gateway Log\n");
        for (int i = 0; i < lines; i++) {
            builder.append("line ").append(i).append(" abcdefghijklmnopqrstuvwxyz\n");
        }
        return builder.toString();
    }
}
