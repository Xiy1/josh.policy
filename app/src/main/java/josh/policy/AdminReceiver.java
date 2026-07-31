package josh.policy;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

public class AdminReceiver extends DeviceAdminReceiver {
    public static ComponentName component(Context c) {
        return new ComponentName(c.getApplicationContext(), AdminReceiver.class);
    }
}
