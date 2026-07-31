package josh.policy;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import java.util.*;

public final class BrowserSweeper {
    private static final Set<String> KEEP =
            new HashSet<>(Arrays.asList("app.vanadium.webview"));

    public static List<String> sweep(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        ComponentName admin = AdminReceiver.component(c);
        List<String> hit = new ArrayList<>();

        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://sweep.invalid/"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);

        for (ResolveInfo ri : c.getPackageManager()
                .queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || KEEP.contains(pkg)) continue;
            try {
                if (dpm.setApplicationHidden(admin, pkg, true)) hit.add(pkg);
            } catch (Exception e) {
                Log.e("POLICY", "sweep " + pkg, e);
            }
        }
        Log.i("POLICY", "swept " + hit);
        return hit;
    }
}
