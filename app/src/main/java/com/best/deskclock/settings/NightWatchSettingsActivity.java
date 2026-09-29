// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static android.Manifest.permission.RECORD_AUDIO;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
import static android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_HOLD_WAKE_LOCK;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_USE_MICROPHONE;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.R;
import com.best.deskclock.alarms.NightWatchService;
import com.best.deskclock.base.BaseSettingsScreenFragment;
import com.best.deskclock.uicomponents.CollapsingToolbarBaseActivity;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.PermissionUtils;
import com.best.deskclock.utils.SdkUtils;

/**
 * Settings screen for the Night Watch feature.
 *
 * <p>Provides:
 * <ul>
 *   <li>Plain-language explanation of what Night Watch does (and what it does NOT do).</li>
 *   <li>"Use microphone" and "Hold wake lock" toggles for A/B testing on XOS.</li>
 *   <li>Runtime RECORD_AUDIO permission request.</li>
 *   <li>Battery-optimization exemption deeplink.</li>
 *   <li>Best-effort OEM autostart / background settings deeplinks
 *       (Infinix/Transsion, Xiaomi, OPPO, Vivo, Huawei, Samsung — component names
 *       are UNVERIFIED; each is wrapped in try/catch and falls back to the standard
 *       app details screen).</li>
 *   <li>Debug-build-only item to cancel the AlarmManager entry and verify the fallback.</li>
 * </ul>
 */
public class NightWatchSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected String getActivityTitle() {
        return null; // Fragment sets the title.
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new NightWatchSettingsFragment())
                    .disallowAddToBackStack()
                    .commit();
        }
    }

    // ──────────────────────────────────────────────────────────
    // Fragment
    // ──────────────────────────────────────────────────────────

    public static class NightWatchSettingsFragment extends BaseSettingsScreenFragment
            implements Preference.OnPreferenceClickListener,
                       Preference.OnPreferenceChangeListener {

        private static final String KEY_MIC_PREF    = "key_night_watch_request_mic_permission";
        private static final String KEY_BATTERY     = "key_night_watch_battery_exemption";
        private static final String KEY_AUTOSTART   = "key_night_watch_autostart";
        private static final String KEY_DEBUG_CANCEL = "key_night_watch_debug_cancel_alarm";
        private static final String KEY_DEBUG_CAT   = "key_night_watch_debug_category";

        private SwitchPreferenceCompat mUseMicPref;
        private SwitchPreferenceCompat mHoldWakeLockPref;

        @Override
        protected String getFragmentTitle() {
            return getString(R.string.night_watch_settings_title);
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.settings_night_watch);

            mUseMicPref       = findPreference(KEY_NIGHT_WATCH_USE_MICROPHONE);
            mHoldWakeLockPref = findPreference(KEY_NIGHT_WATCH_HOLD_WAKE_LOCK);

            if (mUseMicPref != null) {
                mUseMicPref.setOnPreferenceChangeListener(this);
            }
            if (mHoldWakeLockPref != null) {
                mHoldWakeLockPref.setOnPreferenceChangeListener(this);
            }

            setupClickListeners();
            hideDebugCategoryInRelease();
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshMicPermissionSummary();
        }

        @Override
        public boolean onPreferenceClick(@NonNull Preference pref) {
            switch (pref.getKey()) {
                case KEY_MIC_PREF    -> requestMicrophonePermission();
                case KEY_BATTERY     -> openBatteryExemption();
                case KEY_AUTOSTART   -> openAutostartSettings();
                case KEY_DEBUG_CANCEL -> {
                    NightWatchService.debugCancelAlarmManager(requireContext());
                }
            }
            return true;
        }

        @Override
        public boolean onPreferenceChange(@NonNull Preference pref, @NonNull Object newValue) {
            // Both toggles persist automatically via SwitchPreferenceCompat.
            // If the service is currently running, restart it so it picks up the new config.
            if (NightWatchService.isArmed(getPrefs())) {
                NightWatchService.disarm(requireContext());
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> NightWatchService.arm(requireContext()), 300);
            }
            return true;
        }

        // ──────────────────────────────────────────────────────
        // Setup helpers
        // ──────────────────────────────────────────────────────

        private void setupClickListeners() {
            setClickListener(KEY_MIC_PREF);
            setClickListener(KEY_BATTERY);
            setClickListener(KEY_AUTOSTART);
            if (BuildConfig.DEBUG) {
                setClickListener(KEY_DEBUG_CANCEL);
            }
        }

        private void setClickListener(@NonNull String key) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceClickListener(this);
            }
        }

        private void hideDebugCategoryInRelease() {
            PreferenceCategory debugCat = findPreference(KEY_DEBUG_CAT);
            if (debugCat != null) {
                debugCat.setVisible(BuildConfig.DEBUG);
            }
        }

        private void refreshMicPermissionSummary() {
            Preference micPref = findPreference(KEY_MIC_PREF);
            if (micPref == null) return;

            boolean granted = PermissionUtils.isMicrophonePermissionGranted(requireContext());
            micPref.setSummary(granted
                    ? getString(R.string.night_watch_mic_granted_summary)
                    : getString(R.string.night_watch_mic_not_granted_summary));
        }

        // ──────────────────────────────────────────────────────
        // Permission: RECORD_AUDIO
        // ──────────────────────────────────────────────────────

        private void requestMicrophonePermission() {
            if (PermissionUtils.isMicrophonePermissionGranted(requireContext())) {
                // Already granted — show a toast / do nothing.
                return;
            }
            if (SdkUtils.isAtLeastAndroid13()) {
                // API 33+: use requestPermissions.
                requireActivity().requestPermissions(new String[]{RECORD_AUDIO}, 0);
            } else {
                // Older: direct to app settings.
                openAppDetailsSettings();
            }
        }

        // ──────────────────────────────────────────────────────
        // Permission: battery optimization exemption
        // ──────────────────────────────────────────────────────

        @SuppressLint("BatteryLife")
        private void openBatteryExemption() {
            try {
                final Intent intent = new Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                LogUtils.e("NightWatchSettings: battery exemption intent not available", e);
                openAppDetailsSettings();
            }
        }

        // ──────────────────────────────────────────────────────
        // OEM autostart / background settings deeplinks
        // ──────────────────────────────────────────────────────

        /**
         * Tries known OEM "autostart" or "background app" settings screens, each inside
         * its own try/catch. Falls back to standard app-details if none work.
         *
         * <p>Component names are UNVERIFIED and may change across OEM firmware versions.
         * They are taken from community reverse-engineering notes and should be treated
         * as best-effort hints.</p>
         */
        private void openAutostartSettings() {
            final String pkg = requireContext().getPackageName();

            // ── Transsion (Infinix / Tecno / itel) — XOS Phone Master ──────────────────
            // UNVERIFIED: component name varies across XOS versions.
            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.transsion.phonemaster",
                            "com.transsion.phonemaster.ui.activity.AutoStartActivity"))
                    .putExtra("pkg_name", pkg))) return;

            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.itel.phonemaster",
                            "com.itel.phonemaster.ui.activity.AutoStartActivity"))
                    .putExtra("pkg_name", pkg))) return;

            // ── Xiaomi / MIUI / HyperOS ──────────────────────────────────────────────
            // UNVERIFIED: component names from MIUI community.
            if (tryStartActivity(new Intent("miui.intent.action.OP_AUTO_START")
                    .addCategory(Intent.CATEGORY_DEFAULT))) return;

            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity")))) return;

            // ── OPPO / ColorOS ────────────────────────────────────────────────────────
            // UNVERIFIED: component names from ColorOS community.
            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.privacypermissionsentry.PermissionTopActivity")))) return;

            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity")))) return;

            // ── Vivo / OriginOS ───────────────────────────────────────────────────────
            // UNVERIFIED.
            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))) return;

            // ── Huawei / HarmonyOS ────────────────────────────────────────────────────
            // UNVERIFIED.
            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity")))) return;

            // ── Samsung ───────────────────────────────────────────────────────────────
            // UNVERIFIED. On recent OneUI this lives in Device Care.
            if (tryStartActivity(new Intent()
                    .setComponent(new ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity")))) return;

            // ── Fallback: standard app details settings ───────────────────────────────
            openAppDetailsSettings();
        }

        /**
         * @return {@code true} if the activity was started successfully.
         */
        private boolean tryStartActivity(@NonNull Intent intent) {
            try {
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                // Expected on most devices where the OEM component is not present.
                return false;
            }
        }

        private void openAppDetailsSettings() {
            try {
                final Intent intent = new Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", requireContext().getPackageName(), null))
                        .addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                LogUtils.e("NightWatchSettings: app details settings intent not available", e);
            }
        }
    }
}
