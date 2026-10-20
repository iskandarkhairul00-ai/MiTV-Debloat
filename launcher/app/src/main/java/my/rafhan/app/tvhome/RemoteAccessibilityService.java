package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gives the phone remote Back and Home inside any app. It only calls performGlobalAction: it
 * doesn't read window content (canRetrieveWindowContent=false) and ignores events.
 * Enabled by build-and-install.sh, next to Button Mapper's service.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;

    static boolean isOn() {
        return instance != null;
    }

    /** GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME. False if the service isn't enabled. */
    static boolean global(int action) {
        RemoteAccessibilityService s = instance;
        return s != null && s.performGlobalAction(action);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
