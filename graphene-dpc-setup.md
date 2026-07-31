# GrapheneOS Device-Owner Policy Setup

Self-written Device Policy Controller (DPC) for a Pixel 7 running GrapheneOS, built
from Ubuntu. Hides the browser, forces all traffic through a filtering VPN, and
auto-hides any browser installed later.

Built 2026-07-31. Package name: `josh.policy`.

---

## 1. What this achieves

| Goal | Mechanism |
|---|---|
| Vanadium unusable, not just disabled | `setApplicationHidden` — no launcher icon, no Settings entry, no toggle |
| Can't be undone from the phone | Device owner status; only `adb` or a factory reset clears it |
| All traffic through RethinkDNS | `setAlwaysOnVpnPackage(..., lockdown=true)` — no tunnel, no network |
| RethinkDNS can't be removed | `setUninstallBlocked` |
| Newly installed browsers get hidden | Periodic `JobScheduler` sweep, every 15 min |
| Second profile also covered | `createAndManageUser` installs the DPC there as profile owner |

Deliberately **not** applied: `DISALLOW_INSTALL_APPS`, `DISALLOW_ADD_USER`,
`DISALLOW_DEBUGGING_FEATURES`. The last one would kill adb permanently and remove
the only repair path.

---

## 2. Host toolchain (Ubuntu)

```bash
sudo apt install adb android-sdk-platform-tools-common openjdk-21-jdk unzip
```

`android-sdk-platform-tools-common` installs the udev rules, so adb works without
root. Log out and back in if you hit "no permissions".

Android SDK via Android Studio (`sudo snap install android-studio --classic`, run
the setup wizard) or via the standalone command-line tools. Then:

```bash
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
```

**Gotcha:** a JDK is required, not a JRE. `openjdk-25` on this machine was a JRE and
Gradle failed with *"does not provide the required capabilities: [JAVA_COMPILER]"*.
AGP was also not certified for Java 25 at the time. Java 21 works.

---

## 3. Phone preparation

Enable debugging:

- Settings → About phone → tap **Build number** ×7
- Settings → System → **Developer options** → **USB debugging**
- Settings → Security → **USB-C port** → allow data while unlocked (GrapheneOS
  defaults to charging-only)

```bash
adb devices     # accept the RSA prompt on the phone; expect "device"
```

Device owner requires a device with **no secondary users and no accounts**:

```bash
adb shell pm list users          # only UserInfo{0:Owner:...} may remain
adb shell pm remove-user 10      # ERASES that profile
```

Remove every account under Settings → Passwords & accounts, then reboot — the check
sometimes reads stale state.

### Things that do not work (don't waste time)

```bash
adb shell pm uninstall -k --user 0 app.vanadium.browser
# Failure [only root can delete system app for a particular user]
```

Vanadium lives on the verified-boot system partition. Removing it would require
building a custom GrapheneOS image.

```bash
adb shell pm disable-user --user 1 app.vanadium.browser
# Package app.vanadium.browser new state: disabled
```

Misleading. Users 1 and 2 don't exist — Android starts real secondary users at 10.
The command doesn't validate the ID and the read-back returns a default that looks
like success. Real users show `disabled-user`; nonexistent ones show `disabled`.

---

## 4. Project layout

```bash
mkdir -p ~/workspace/policy-dpc/app/src/main/java/josh/policy
mkdir -p ~/workspace/policy-dpc/app/src/main/res/xml
```

```
~/workspace/policy-dpc/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── local.properties
├── dpc.jks                          ← signing key, BACK THIS UP
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/josh/policy/
        │   ├── AdminReceiver.java
        │   ├── BrowserSweeper.java
        │   ├── MainActivity.java
        │   └── SweepJob.java
        └── res/xml/device_admin.xml
```

The Java directory path **must** mirror the package name. `josh.policy` →
`java/josh/policy/`.

---

## 5. Build configuration

### `settings.gradle`

```groovy
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "policy-dpc"
include ':app'
```

### `build.gradle` (root)

```groovy
plugins {
    id 'com.android.application' version '9.3.0' apply false
}
```

### `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
```

### `gradle/wrapper/gradle-wrapper.properties`

Change **only** the `distributionUrl` line. Note the escaped colon.

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

AGP 9.3 requires Gradle ≥ 9.5.0. AGP and Gradle versions must be paired — mismatches
produce either a hard version-check failure or `compileSdk` warnings.

### `local.properties`

```properties
sdk.dir=/home/josh/Android/Sdk
```

### `app/build.gradle`

```groovy
plugins { id 'com.android.application' }

android {
    namespace 'josh.policy'
    compileSdk 35

    defaultConfig {
        applicationId "josh.policy"
        minSdk 31
        targetSdk 35
        versionCode 1
        versionName "1.0"
    }

    signingConfigs {
        release {
            storeFile file("../dpc.jks")
            storePassword "YOUR_KEYSTORE_PASSWORD"
            keyAlias "dpc"
            keyPassword "YOUR_KEYSTORE_PASSWORD"
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.release
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

`applicationId` must contain at least one dot. Raise `compileSdk`/`targetSdk` to
whatever platform `sdkmanager` installed.

---

## 6. Signing key

```bash
cd ~/workspace/policy-dpc
keytool -genkeypair -v -keystore dpc.jks -alias dpc \
        -keyalg RSA -keysize 4096 -validity 10000
```

The keystore password is one you **invent at the prompt**; it then goes into
`app/build.gradle` verbatim. Press Enter at the key-password prompt to reuse the
store password. Name/organisation fields are cosmetic — `Unknown` is fine.

> **Back up `dpc.jks` off the machine.** Device owner updates require the same
> signing key. Lose it and the only way to change the policy is a factory reset.

---

## 7. Source files

### `app/src/main/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW"/>
            <category android:name="android.intent.category.BROWSABLE"/>
            <data android:scheme="https"/>
        </intent>
    </queries>

    <application android:label="Policy">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <receiver
            android:name=".AdminReceiver"
            android:permission="android.permission.BIND_DEVICE_ADMIN"
            android:exported="true">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin"/>
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED"/>
            </intent-filter>
        </receiver>

        <service android:name=".SweepJob"
                 android:permission="android.permission.BIND_JOB_SERVICE"
                 android:exported="false"/>
    </application>
</manifest>
```

The `<queries>` block is **required**. Android 11+ package visibility filtering
would otherwise make `queryIntentActivities` return an incomplete list and the
sweeper would silently find nothing.

### `app/src/main/res/xml/device_admin.xml`

```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies/>
</device-admin>
```

Can be empty — a device owner's powers come from ownership, not these entries — but
the file must exist or the receiver is rejected.

### `AdminReceiver.java`

```java
package josh.policy;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

public class AdminReceiver extends DeviceAdminReceiver {
    public static ComponentName component(Context c) {
        return new ComponentName(c.getApplicationContext(), AdminReceiver.class);
    }
}
```

### `MainActivity.java`

Policy is applied here, **not** in `onEnabled` — `dpm set-device-owner` fires
`onEnabled` before ownership is actually granted, so DPM calls from there throw
`SecurityException`.

```java
package josh.policy;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
            dpm.setAlwaysOnVpnPackage(admin, VPN, true);   // true = lockdown
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
```

Passing `admin` as the third argument to `createAndManageUser` installs this DPC
into the new user as **profile owner**, which is what lets it hide packages there.
The button is guarded by `isOwner` because `createAndManageUser` is device-owner-only.

### `BrowserSweeper.java`

```java
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
```

**How detection works.** A general-purpose browser declares `ACTION_VIEW` +
`BROWSABLE` with no host restriction. Apps with deep links (WhatsApp, WeChat)
restrict theirs to their own domains, so probing with `sweep.invalid` matches only
real browsers.

**Keep the self-skip line.** If the DPC ever hides itself, it can't be launched, its
jobs are cancelled, and there is **no adb command to un-hide** — `pm` has
enable/disable and suspend/unsuspend, but hidden state is DPM-only. That would mean
a factory reset.

`app.vanadium.webview` is exempt because other apps break without a WebView provider.

### `SweepJob.java`

```java
package josh.policy;

import android.app.job.*;

public class SweepJob extends JobService {
    public static final int ID = 1;
    @Override public boolean onStartJob(JobParameters p) {
        BrowserSweeper.sweep(this);
        return false;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
```

`ACTION_PACKAGE_ADDED` is not an implicit-broadcast exemption, so a manifest
receiver won't fire on modern Android. A periodic job is the reliable trigger;
15 minutes is the scheduler's floor.

---

## 8. Build

```bash
cd ~/workspace/policy-dpc
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Verify the testOnly flag — do not skip

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging \
    app/build/outputs/apk/release/app-release.apk | grep -i testonly
```

**No output is the pass condition.** An APK flagged `testOnly="true"` can have its
device ownership stripped with a single `dpm remove-active-admin`. Android Studio's
Run button injects that flag into debug builds — which is why release is built from
the terminal.

---

## 9. Provision

```bash
adb install app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner josh.policy/.AdminReceiver
```

Expected:

```
Success: Device owner set to package josh.policy/.AdminReceiver
Active admin set to component josh.policy/.AdminReceiver
```

*"Not allowed to set the device owner"* → an account or profile is still present.

**Then open the Policy app** (or `adb shell am start -n josh.policy/.MainActivity`).
The policy only applies when `MainActivity` runs.

---

## 10. RethinkDNS

Chosen over DNSNet, whose manifest sets `SUPPORTS_ALWAYS_ON=false` — the framework
honours that flag before checking privileges, so `setAlwaysOnVpnPackage` throws
`UnsupportedOperationException` no matter what the DPC is allowed to do.

Check any candidate before committing:

```bash
adb shell pm path com.celzero.bravedns
adb pull <path>/base.apk rethink.apk        # path to base.apk, not the directory
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump xmltree \
    --file AndroidManifest.xml rethink.apk | grep -i -A3 always
```

`SUPPORTS_ALWAYS_ON=true` or absent is fine; `false` means it can't be locked down.

**Configure RethinkDNS by hand first** — resolver, blocklists, confirm normal apps
work. Lockdown means a broken config leaves the phone with no network and adb as the
only repair path.

Recommended universal firewall rules inside RethinkDNS:

- **Block newly installed apps** — a new app has no network from the moment it's
  installed. This closes the 15-minute sweeper gap far better than anything the DPC
  can do.
- **Prevent DNS bypass** — blocks connections to IPs that didn't come from a DNS
  answer Rethink issued. This is what stops Tor, which dials hardcoded relay
  addresses.

---

## 11. Second user profile

Tap **Create managed user** in the Policy app (device owner only). Then:

```bash
adb shell pm list users                                # note the new id, e.g. 10
adb shell am switch-user 10
```

Install and configure RethinkDNS **in that profile** — apps aren't shared across
users — then apply the policy there:

```bash
adb shell am start --user 10 -n josh.policy/.MainActivity
```

Everything here is per-user: hidden state, always-on VPN, preferred activities.
A profile created outside the DPC (via Settings) is unmanaged and gets a working
browser. `DISALLOW_ADD_USER` would prevent that, at the cost of losing GrapheneOS
profiles entirely.

---

## 12. Verification

```bash
# device owner
adb shell dumpsys device_policy | head -20

# hidden state — authoritative
adb shell dumpsys package app.vanadium.browser | grep -iE "hidden|installed=|enabled="

# VPN lockdown
adb shell settings get secure always_on_vpn_app
adb shell settings get secure always_on_vpn_lockdown      # want 1

# sweep job
adb shell dumpsys jobscheduler | grep -A5 josh.policy

# policy run log
adb shell am start -n josh.policy/.MainActivity
adb logcat -d -s POLICY

# no browser resolves a web URL
adb shell am start -a android.intent.action.VIEW -d https://example.com
# Error: Activity not started, unable to resolve Intent  ← correct
```

**`adb shell pm list packages` still shows hidden packages** — shell has elevated
visibility. Trust `dumpsys package`, not `pm list`.

Reboot once and re-check: the VPN should come up on its own and the job should
survive.

---

## 13. Updating the policy

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n josh.policy/.MainActivity
adb logcat -d -s POLICY
```

`adb install -r` with the **same signing key** preserves device-owner status; a
different key is rejected outright. This is why adb must stay enabled — it's the
only way to change or repair anything.

---

## 14. App states, for reference

| State | On disk | Data | Visible | Who can undo |
|---|---|---|---|---|
| Normal | yes | yes | yes | — |
| Disabled (`pm disable-user`) | yes | yes | listed as disabled | user, in Settings |
| Suspended | yes | yes | greyed out | device owner |
| **Hidden** | yes | yes | **no trace** | device/profile owner only |
| Uninstalled for user | system apps only | wiped | no | adb `install-existing` |
| Uninstalled | no | wiped | no | reinstall |

Useful side effect: a hidden package is still installed, so a fresh install of the
same package hits a conflict — and there's no UI to remove the conflicting copy.
Install and uninstall are both blocked from the phone alone. To remove one:

```bash
adb shell pm uninstall --user 0 org.torproject.torbrowser
adb shell pm uninstall --user 10 org.torproject.torbrowser
```

---

## 15. Known gaps

- **Any tunnel defeats DNS filtering.** Tor never resolves the destination — that
  happens inside the encrypted circuit. RethinkDNS sees only a relay IP. Verified in
  practice. "Prevent DNS bypass" is the counter.
- **15-minute sweep window.** A newly installed browser works until the next sweep.
  RethinkDNS "block newly installed apps" closes it.
- **A browser opened from its own icon** bypasses intent-based blocking entirely.
  Only hiding stops it.
- **WebView still renders web content** inside other apps. Custom Tabs and OAuth
  login flows may break now that no browser exists — test WeChat and anything using
  third-party sign-in.
- **adb is the master key.** Everything here is reversible from the laptop in one
  command. That's deliberate: the lock holds against the phone in hand, not against
  a deliberate session at the desk.

### Not implemented

- `DISALLOW_INSTALL_APPS` — would block all installs, not just browsers
- `DISALLOW_ADD_USER` — would forfeit GrapheneOS profiles
- `DISALLOW_DEBUGGING_FEATURES` — would kill adb permanently; **one-way**
- http/https interception via a dead-end `BlockedActivity` — rejected as too risky
  for deep links, and largely redundant while no browser is installed
- A release receiver calling `clearDeviceOwnerApp()` — no escape hatch exists; the
  only exit is a factory reset

---

## 16. Gotchas that cost time

| Symptom | Cause |
|---|---|
| `only root can delete system app` | Vanadium is on the verified-boot system partition |
| `pm disable-user --user 1` "succeeds" | User 1 doesn't exist; read-back returns a default |
| Blank screen in the Policy app | Default `TextView` renders dark-on-dark; set colours explicitly |
| `does not provide JAVA_COMPILER` | Toolchain pointed at a JRE, not a JDK |
| `Minimum supported Gradle version is 9.5.0` | AGP and Gradle versions must be paired |
| `failed opening zip: I/O error` | `adb pull` of a directory created a local dir; pull `base.apk` specifically |
| Java code "not compiling" | Statements placed at class level instead of inside a method |
| `pm list packages` shows a hidden app | Shell has elevated package visibility; use `dumpsys package` |
| Sweeper finds nothing | Missing `<queries>` block (Android 11+ visibility filtering) |
