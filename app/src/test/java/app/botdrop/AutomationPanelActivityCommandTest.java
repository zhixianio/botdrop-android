package app.botdrop;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutomationPanelActivityCommandTest {

    @Test
    public void testReinstallDependenciesCommand_usesAptPackageForUiautomator2() throws Exception {
        String command = invokePrivateCommand("buildReinstallDependenciesCommand");

        assertFalse(command.contains("python-pip"));
        assertFalse(command.contains("pip not available after reinstall"));
    }

    @Test
    public void testCheckU2AutomatorInstalledCommand_usesDpkg() throws Exception {
        String command = invokePrivateCommand("buildCheckU2AutomatorInstalledCommand");

        assertTrue(command.contains("dpkg -s python-uiautomator2-botdrop"));
        assertFalse(command.contains("pip show uiautomator2"));
        assertFalse(command.contains("pip3 show uiautomator2"));
    }

    @Test
    public void testInstallU2AutomatorCommand_usesAptInstall() throws Exception {
        String command = invokePrivateCommand("buildInstallU2AutomatorCommand");

        assertTrue(command.contains("apt update"));
        assertTrue(command.contains("apt install -y python-uiautomator2-botdrop"));
        assertFalse(command.contains("git+https://github.com/lay2dev/uiautomator2.git"));
        assertFalse(command.contains("PIP_CMD"));
        assertFalse(command.contains("pip not found"));
    }

    private String invokePrivateCommand(String methodName) throws Exception {
        AutomationPanelActivity activity = Robolectric.buildActivity(AutomationPanelActivity.class)
            .create()
            .get();
        Method method = AutomationPanelActivity.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(activity);
    }
}
