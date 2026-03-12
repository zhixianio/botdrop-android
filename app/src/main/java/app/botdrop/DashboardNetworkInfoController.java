package app.botdrop;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public final class DashboardNetworkInfoController {

    private static final String TAG = "DashboardNetworkInfo";
    private static final String FALLBACK_IP = "<device-ip>";
    private static final String FALLBACK_PASSWORD = "<not set>";

    private final Activity mActivity;
    private final View mSshCard;
    private final TextView mSshInfoText;

    DashboardNetworkInfoController(@NonNull Activity activity,
                                  @NonNull View sshCard,
                                  @NonNull TextView sshInfoText) {
        mActivity = activity;
        mSshCard = sshCard;
        mSshInfoText = sshInfoText;
    }

    void refreshSshInfo() {
        String ip = getDeviceIp();
        if (TextUtils.isEmpty(ip)) {
            ip = FALLBACK_IP;
        }

        String password = readSshPassword();
        if (TextUtils.isEmpty(password)) {
            password = FALLBACK_PASSWORD;
        }

        mSshInfoText.setText(mActivity.getString(R.string.botdrop_ssh_password_label, ip, password));
        mSshCard.setVisibility(View.VISIBLE);
    }

    @Nullable
    private String readSshPassword() {
        try {
            File pwFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh_password");
            if (!pwFile.exists()) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(pwFile))) {
                String password = reader.readLine();
                if (password != null) {
                    return password.trim();
                }
            }
        } catch (Exception e) {
            Logger.logError(TAG, "Failed to read SSH password: " + e.getMessage());
        }
        return null;
    }

    @Nullable
    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(TAG, "Failed to get device IP: " + e.getMessage());
        }
        return null;
    }
}
