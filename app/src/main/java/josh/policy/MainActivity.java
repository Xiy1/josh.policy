package josh.policy;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.content.IntentFilter;

public class MainActivity extends Activity {
    private static final String TARGET = "app.vanadium.browser";
    private static final String VPN = "com.celzero.bravedns";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        root.setBackgroundColor(0xFF000000);
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        root.addView(tv);
        setContentView(root);

        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName admin = AdminReceiver.component(this);

        boolean isOwner = dpm.isDeviceOwnerApp(getPackageName());
        if (!isOwner && !dpm.isProfileOwnerApp(getPackageName())) {
            tv.setText("Not managed in this user.");
            return;
        }

        StringBuilder log = new StringBuilder();

        try {
            boolean ok = dpm.setApplicationHidden(admin, TARGET, true);
            log.append("hide ").append(TARGET).append(": ").append(ok).append("\n");
            log.append("verified hidden: ")
               .append(dpm.isApplicationHidden(admin, TARGET)).append("\n");
        } catch (Exception e) {
            log.append("hide failed: ").append(e).append("\n");
            Log.e("POLICY", "hide failed", e);
        }

        dpm.setUninstallBlocked(admin, getPackageName(), true);
        log.append("self-uninstall blocked\n");

        try {
            dpm.setAlwaysOnVpnPackage(admin, VPN, true);
            dpm.setUninstallBlocked(admin, VPN, true);
            log.append("alwaysOnVpn: ")
               .append(dpm.getAlwaysOnVpnPackage(admin)).append("\n");
        } catch (PackageManager.NameNotFoundException e) {
            log.append("VPN not installed in this user\n");
            Log.e("POLICY", "VPN package not installed", e);
        } catch (UnsupportedOperationException e) {
            log.append("VPN opted out of always-on\n");
            Log.e("POLICY", "opted out", e);
        }

        tv.setText(log.toString());
        Log.i("POLICY", log.toString());

        if (isOwner) {
            Button add = new Button(this);
            add.setText("Create managed user");
            add.setOnClickListener(v -> {
                UserHandle u = dpm.createAndManageUser(admin, "Second", admin, null,
                        DevicePolicyManager.SKIP_SETUP_WIZARD
                      | DevicePolicyManager.LEAVE_ALL_SYSTEM_APPS_ENABLED);
                Log.i("POLICY", "created " + u);
                tv.append("created user: " + u + "\n");
            });
            root.addView(add);
        }

        tv.append("swept: " + BrowserSweeper.sweep(this) + "\n");

        getSystemService(JobScheduler.class).schedule(
            new JobInfo.Builder(SweepJob.ID, new ComponentName(this, SweepJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }
}
