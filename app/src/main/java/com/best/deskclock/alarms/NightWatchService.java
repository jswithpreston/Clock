// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_ENABLED;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_HOLD_WAKE_LOCK;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_TARGET_ALARM_TIME;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_WATCH_USE_MICROPHONE;
import static com.best.deskclock.utils.NotificationUtils.NIGHT_WATCH_CHANNEL_ID;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.DeskClock;
import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.NotificationUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.Utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Night Watch — foreground service that keeps the app process alive overnight so that the
 * alarm reliably fires on aggressive OEM kernels (Infinix XOS, etc.).
 *
 * <p>Strategy (mirrors Sleep Cycle / com.northcube.sleepcycle SleepAnalysisService):
 * <ol>
 *   <li>Runs as a foreground service with an ongoing HIGH-importance notification.</li>
 *   <li>Holds a PARTIAL_WAKE_LOCK so the CPU cannot sleep.</li>
 *   <li>Opens the microphone (if permitted and enabled) in a discard loop so the AudioIn
 *       wake lock is also held — identical to the pattern observed in Sleep Cycle via
 *       {@code adb shell dumpsys power}. No audio is stored, analysed, or transmitted.</li>
 *   <li>A wall-clock poll every ~30 s re-reads the next alarm from the DB and re-registers
 *       it using the existing {@link AlarmStateManager} scheduling. This uses a
 *       {@link ScheduledExecutorService} inside the service — NOT AlarmManager — so XOS
 *       cannot wipe it.</li>
 *   <li>A precise one-shot timer fires at alarmTime + 5 s grace. If the instance is still
 *       not in FIRED state, the service sends the same intent that AlarmManager would have
 *       sent, going through the normal AlarmService/AlarmStateManager path.</li>
 *   <li>START_STICKY: on system restart the service re-reads target time from prefs and
 *       continues if the alarm is still in the future.</li>
 * </ol>
 *
 * <h3>Amendment-2 confirmation (no loop / safe transitions):</h3>
 * <ul>
 *   <li>No re-scheduling loop: {@code setFiredState()} schedules only a MISSED timeout, not
 *       another FIRED event. The MISSED path does not loop back to FIRED.</li>
 *   <li>SILENT_STATE / NOTIFICATION_STATE → FIRED is safe: {@code handleIntent()} calls
 *       {@code setAlarmState(..., FIRED_STATE)} which calls {@code setFiredState()}, which
 *       writes the new state to the DB and causes AlarmService to start the ringtone. There
 *       is no prior-state check that would block the transition.</li>
 *   <li>Double-alarm guard: {@code AlarmService.startAlarm()} returns early if
 *       {@code mCurrentAlarm.mId == instanceId}. Additionally, NightWatch re-queries the
 *       instance from the DB immediately before firing and checks
 *       {@code instance.mAlarmState < AlarmInstance.FIRED_STATE}.</li>
 * </ul>
 */
public class NightWatchService extends Service {

    // ──────────────────────────────────────────────────────────
    // Intent actions
    // ──────────────────────────────────────────────────────────

    /** Start / arm the service. Caller must put EXTRA_TARGET_ALARM_TIME. */
    public static final String ACTION_START = "com.best.deskclock.NIGHT_WATCH_START";

    /** Stop the service from the notification's "Stop" action or from the UI. */
    public static final String ACTION_STOP = "com.best.deskclock.NIGHT_WATCH_STOP";

    /**
     * DEBUG-ONLY: cancel the AlarmManager entries for the next alarm instance without
     * stopping this service. Use this to verify that the fallback fires the alarm.
     *
     * <p>Trigger via adb:
     * <pre>
     *   adb shell am broadcast -a com.best.deskclock.NIGHT_WATCH_DEBUG_CANCEL_ALARM \
     *       -n com.best.deskclock.debug/com.best.deskclock.alarms.NightWatchService
     * </pre>
     * Or from Settings → Night Watch → "Cancel AlarmManager entry (debug)" (debug builds only).
     * </p>
     */
    public static final String ACTION_DEBUG_CANCEL_ALARM_MANAGER =
            "com.best.deskclock.NIGHT_WATCH_DEBUG_CANCEL_ALARM";

    /** Extra: target alarm time in millis (long). */
    public static final String EXTRA_TARGET_ALARM_TIME = "extra_target_alarm_time_ms";

    // ──────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────

    /**
     * Notification ID. Coordinated with other IDs in NotificationModel:
     * MAX_VALUE-4 = alarm group, MAX_VALUE-6 = KeepAlive → we use MAX_VALUE-7.
     */
    public static final int NOTIFICATION_ID = Integer.MAX_VALUE - 7;

    // ──────────────────────────────────────────────────────────
    // Timing constants
    // ──────────────────────────────────────────────────────────

    /** Grace period after the scheduled alarm time before the fallback fires (ms). */
    private static final long FALLBACK_GRACE_MS = 5_000L;

    /** Maximum wake-lock duration to avoid running forever if logic goes wrong (16 h). */
    private static final long MAX_WAKE_LOCK_DURATION_MS = 16L * 60L * 60L * 1_000L;

    /** How often (ms) the wall-clock poll re-queries the DB and re-registers the alarm. */
    private static final long POLL_INTERVAL_MS = 30_000L;

    /** AudioRecord sample rate — lowest valid rate, minimal CPU impact. */
    private static final int AUDIO_SAMPLE_RATE = 8000;

    // ──────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────

    private SharedPreferences mPrefs;
    private PowerManager.WakeLock mWakeLock;
    private AudioRecord mAudioRecord;
    private Thread mMicThread;
    private final AtomicBoolean mMicRunning = new AtomicBoolean(false);
    private ScheduledExecutorService mScheduler;
    private ScheduledFuture<?> mPollFuture;
    private ScheduledFuture<?> mFallbackFuture;

    /** Target alarm time in millis; updated whenever the next alarm changes. */
    private volatile long mTargetAlarmTimeMs = 0L;

    // ──────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = getDefaultSharedPreferences(this);

        if (SdkUtils.isAtLeastAndroid8()) {
            NotificationUtils.createChannel(this, NIGHT_WATCH_CHANNEL_ID);
        }

        mScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        LogUtils.v("NightWatchService.onStartCommand() action=%s",
                intent != null ? intent.getAction() : "null (sticky restart)");

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (BuildConfig.DEBUG
                && intent != null
                && ACTION_DEBUG_CANCEL_ALARM_MANAGER.equals(intent.getAction())) {
            handleDebugCancelAlarmManager();
            return START_NOT_STICKY;
        }

        // Determine target alarm time.
        long targetMs = 0L;
        if (intent != null && intent.hasExtra(EXTRA_TARGET_ALARM_TIME)) {
            targetMs = intent.getLongExtra(EXTRA_TARGET_ALARM_TIME, 0L);
        }
        if (targetMs <= 0L) {
            // Sticky restart or missing extra — restore from prefs.
            targetMs = mPrefs.getLong(KEY_NIGHT_WATCH_TARGET_ALARM_TIME, 0L);
        }

        // If the target is already in the past by more than the grace period, nothing to do.
        final long now = System.currentTimeMillis();
        if (targetMs > 0L && targetMs < now - FALLBACK_GRACE_MS * 2) {
            LogUtils.i("NightWatch: target alarm already passed — stopping.");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Persist state so we can recover after a sticky restart.
        mTargetAlarmTimeMs = targetMs;
        mPrefs.edit()
                .putBoolean(KEY_NIGHT_WATCH_ENABLED, true)
                .putLong(KEY_NIGHT_WATCH_TARGET_ALARM_TIME, targetMs)
                .apply();

        // Raise the foreground notification first (required before any work on API 26+).
        startForegroundWithNotification(targetMs);

        // Acquire wake lock (if enabled in settings).
        if (SettingsDAO.isNightWatchHoldWakeLock(mPrefs)) {
            acquireWakeLock(targetMs);
        }

        // Start microphone loop (if enabled and permission granted).
        if (SettingsDAO.isNightWatchUseMicrophone(mPrefs)) {
            startMicrophoneLoop();
        }

        // Schedule the wall-clock poll and the precise fallback timer.
        schedulePollAndFallback(targetMs);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        LogUtils.v("NightWatchService.onDestroy()");

        // Cancel all scheduled tasks first.
        shutdownScheduler();

        // Stop microphone loop.
        stopMicrophoneLoop();

        // Release wake lock.
        releaseWakeLock();

        // Clear the "armed" flag in prefs.
        mPrefs.edit()
                .putBoolean(KEY_NIGHT_WATCH_ENABLED, false)
                .remove(KEY_NIGHT_WATCH_TARGET_ALARM_TIME)
                .apply();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    // ──────────────────────────────────────────────────────────
    // Foreground notification
    // ──────────────────────────────────────────────────────────

    private void startForegroundWithNotification(long targetAlarmTimeMs) {
        final Notification notification = buildNotification(this, targetAlarmTimeMs,
                SettingsDAO.getLanguageCode(mPrefs));

        int fgsType = 0;
        if (SdkUtils.isAtLeastAndroid14()) {
            // On API 34+, use MICROPHONE if permission is granted, otherwise SPECIAL_USE.
            if (hasMicrophonePermission()) {
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            } else {
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
        } else if (SdkUtils.isAtLeastAndroid10()) {
            // API 29-33: MICROPHONE type available but not mandatory for permission; use it if
            // permission granted so the AudioIn wake lock appears in dumpsys output.
            if (hasMicrophonePermission()) {
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType);
        } catch (Exception e) {
            // Defensive: on some ROMs startForeground with a type can throw even with the right
            // manifest declaration. Fall back to no-type.
            LogUtils.e("NightWatch: startForeground with type failed, retrying without type", e);
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0);
            } catch (Exception e2) {
                LogUtils.e("NightWatch: startForeground fallback also failed — stopping", e2);
                stopSelf();
            }
        }
    }

    /**
     * Builds the ongoing Night Watch notification.
     *
     * @param context          application context
     * @param targetAlarmTimeMs target alarm fire time in ms (0 = unknown)
     * @param languageCode     user language preference
     */
    @NonNull
    public static Notification buildNotification(@NonNull Context context,
                                                  long targetAlarmTimeMs,
                                                  @NonNull String languageCode) {
        final Context localCtx = Utils.getLocalizedContext(context, languageCode);

        // Format alarm time for display.
        final String alarmTimeStr;
        if (targetAlarmTimeMs > 0L) {
            final Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(targetAlarmTimeMs);
            final boolean is24h = DateFormat.is24HourFormat(localCtx);
            final String pattern = is24h ? "HH:mm" : "h:mm a";
            alarmTimeStr = new SimpleDateFormat(pattern, Locale.getDefault()).format(cal.getTime());
        } else {
            alarmTimeStr = "—";
        }

        final String contentText = localCtx.getString(
                R.string.night_watch_notification_text, alarmTimeStr);

        // "Stop" action intent.
        final Intent stopIntent = new Intent(context, NightWatchService.class)
                .setAction(ACTION_STOP);
        final PendingIntent stopPendingIntent = PendingIntent.getService(
                context, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tap opens DeskClock on the alarms tab.
        final Intent openIntent = new Intent(context, DeskClock.class);
        final PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, NIGHT_WATCH_CHANNEL_ID)
                .setContentTitle(localCtx.getString(R.string.night_watch_notification_title))
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setSmallIcon(R.drawable.ic_tab_alarm_static)
                .setColor(ContextCompat.getColor(context, R.color.notificationColor))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .addAction(R.drawable.ic_close,
                        localCtx.getString(R.string.night_watch_stop_action),
                        stopPendingIntent)
                .build();
    }

    // ──────────────────────────────────────────────────────────
    // Wake lock
    // ──────────────────────────────────────────────────────────

    @SuppressWarnings("WakelockTimeout") // Timeout is set below; @SuppressLint not needed for Lint
    private void acquireWakeLock(long targetAlarmTimeMs) {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            return;
        }
        final PowerManager pm = getApplicationContext().getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deskclock:nightwatch");

        // Timeout = (alarmTime - now) + 10 min margin, but capped at MAX_WAKE_LOCK_DURATION_MS.
        final long now = System.currentTimeMillis();
        final long remaining = targetAlarmTimeMs > 0L ? (targetAlarmTimeMs - now) : 0L;
        final long timeoutMs = Math.min(
                Math.max(remaining + 10L * 60L * 1_000L, 10L * 60L * 1_000L),
                MAX_WAKE_LOCK_DURATION_MS);

        mWakeLock.acquire(timeoutMs);
        LogUtils.i("NightWatch: wake lock acquired for %d ms", timeoutMs);
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            LogUtils.i("NightWatch: wake lock released");
        }
        mWakeLock = null;
    }

    // ──────────────────────────────────────────────────────────
    // Microphone loop
    // ──────────────────────────────────────────────────────────

    private void startMicrophoneLoop() {
        if (!hasMicrophonePermission()) {
            LogUtils.i("NightWatch: RECORD_AUDIO not granted — skipping mic loop");
            return;
        }

        final int minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            LogUtils.e("NightWatch: AudioRecord.getMinBufferSize() returned error — skipping mic loop");
            return;
        }

        final int bufSize = minBuf * 2;

        try {
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (IllegalArgumentException e) {
            LogUtils.e("NightWatch: AudioRecord init failed — skipping mic loop", e);
            return;
        }

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            LogUtils.e("NightWatch: AudioRecord not initialized (mic busy?) — skipping mic loop");
            mAudioRecord.release();
            mAudioRecord = null;
            return;
        }

        mMicRunning.set(true);
        mMicThread = new Thread(() -> {
            final short[] scratch = new short[bufSize / 2];
            try {
                mAudioRecord.startRecording();
                LogUtils.i("NightWatch: mic loop started (audio discarded, never stored or sent)");

                while (mMicRunning.get()) {
                    // Read and immediately discard — purpose is only to hold the AudioIn wake lock.
                    final int read = mAudioRecord.read(scratch, 0, scratch.length);
                    if (read < 0) {
                        LogUtils.w("NightWatch: AudioRecord.read() error %d — stopping mic loop", read);
                        break;
                    }
                }
            } catch (Exception e) {
                LogUtils.e("NightWatch: unexpected error in mic loop", e);
            } finally {
                try {
                    if (mAudioRecord != null) {
                        mAudioRecord.stop();
                    }
                } catch (Exception ignored) {
                }
                LogUtils.i("NightWatch: mic loop stopped");
            }
        }, "NightWatch-MicLoop");
        mMicThread.setDaemon(true);
        mMicThread.start();
    }

    private void stopMicrophoneLoop() {
        mMicRunning.set(false);
        if (mMicThread != null) {
            mMicThread.interrupt();
            mMicThread = null;
        }
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
                mAudioRecord.release();
            } catch (Exception ignored) {
            }
            mAudioRecord = null;
        }
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ──────────────────────────────────────────────────────────
    // Scheduler: wall-clock poll + precise fallback timer
    // ──────────────────────────────────────────────────────────

    private void schedulePollAndFallback(long targetAlarmTimeMs) {
        // Cancel any existing tasks before (re-)scheduling.
        cancelScheduledTasks();

        // Wall-clock poll: re-queries the DB and re-registers the alarm every POLL_INTERVAL_MS.
        mPollFuture = mScheduler.scheduleAtFixedRate(
                this::pollAndReregisterAlarm,
                POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Precise fallback: fires at targetAlarmTimeMs + grace.
        final long now = System.currentTimeMillis();
        final long fireDelay = Math.max(0L, (targetAlarmTimeMs + FALLBACK_GRACE_MS) - now);
        mFallbackFuture = mScheduler.schedule(
                this::maybeFallbackFireAlarm,
                fireDelay, TimeUnit.MILLISECONDS);

        LogUtils.i("NightWatch: fallback scheduled in %d ms (%.1f min)",
                fireDelay, fireDelay / 60_000.0);
    }

    private void cancelScheduledTasks() {
        if (mPollFuture != null) {
            mPollFuture.cancel(false);
            mPollFuture = null;
        }
        if (mFallbackFuture != null) {
            mFallbackFuture.cancel(false);
            mFallbackFuture = null;
        }
    }

    private void shutdownScheduler() {
        cancelScheduledTasks();
        if (mScheduler != null && !mScheduler.isShutdown()) {
            mScheduler.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────────────────
    // Wall-clock poll
    // ──────────────────────────────────────────────────────────

    /**
     * Called every ~30 s on the scheduler thread.
     * <p>
     * Re-queries the next alarm from the DB and:
     * <ul>
     *   <li>Re-registers it via {@link AlarmStateManager#fixAlarmInstances} so any AlarmManager
     *       entry that XOS may have wiped is restored.</li>
     *   <li>Updates the target time in case the user edited, snoozed, or disabled the alarm.</li>
     *   <li>Stops the service if no alarms remain or if the target time has passed with margin.</li>
     *   <li>Reschedules the fallback timer if the target time changed.</li>
     * </ul>
     */
    private void pollAndReregisterAlarm() {
        try {
            final Context ctx = getApplicationContext();
            final AlarmInstance nextAlarm = AlarmInstance.getNextFiringAlarm(ctx);

            if (nextAlarm == null) {
                LogUtils.i("NightWatch poll: no upcoming alarms — stopping service");
                stopSelf();
                return;
            }

            final long newTargetMs = nextAlarm.getAlarmTime().getTimeInMillis();
            final long now = System.currentTimeMillis();

            // Check if the alarm already passed with a generous margin.
            if (newTargetMs < now - 2 * FALLBACK_GRACE_MS) {
                LogUtils.i("NightWatch poll: target alarm already passed — stopping service");
                stopSelf();
                return;
            }

            // If the target changed (edit / snooze / new alarm enabled), reschedule.
            if (newTargetMs != mTargetAlarmTimeMs) {
                LogUtils.i("NightWatch poll: target alarm changed from %d to %d — rescheduling",
                        mTargetAlarmTimeMs, newTargetMs);
                mTargetAlarmTimeMs = newTargetMs;
                mPrefs.edit().putLong(KEY_NIGHT_WATCH_TARGET_ALARM_TIME, newTargetMs).apply();
                schedulePollAndFallback(newTargetMs);

                // Update notification to show the new alarm time.
                updateNotification(newTargetMs);
                return; // schedulePollAndFallback cancels + recreates the poll future; return now.
            }

            // Re-register the alarm — idempotent, restores any wiped AlarmManager entries.
            // Run on the disk-IO executor because fixAlarmInstances touches the DB.
            AlarmStateManager.fixAlarmInstances(ctx, getDefaultSharedPreferences(ctx));

            LogUtils.v("NightWatch poll: alarm re-registered, target in %d s",
                    (newTargetMs - now) / 1000L);

        } catch (Exception e) {
            LogUtils.e("NightWatch: exception in pollAndReregisterAlarm", e);
        }
    }

    // ──────────────────────────────────────────────────────────
    // Precise fallback fire
    // ──────────────────────────────────────────────────────────

    /**
     * Called at {@code targetAlarmTimeMs + FALLBACK_GRACE_MS} by the scheduler.
     * <p>
     * Re-queries the instance from the DB. Only fires the fallback if:
     * <ol>
     *   <li>An instance with that time exists.</li>
     *   <li>Its state is LESS THAN {@link AlarmInstance#FIRED_STATE} (i.e. not already ringing,
     *       not missed, not dismissed).</li>
     * </ol>
     * This prevents double-firing if the normal AlarmManager path already worked.
     */
    private void maybeFallbackFireAlarm() {
        try {
            final Context ctx = getApplicationContext();
            final SharedPreferences prefs = getDefaultSharedPreferences(ctx);

            // Always re-query — never trust a stale in-memory reference.
            final AlarmInstance instance = AlarmInstance.getNextFiringAlarm(ctx);

            if (instance == null) {
                LogUtils.i("NightWatch fallback: no upcoming instance found — stopping service");
                stopSelf();
                return;
            }

            final long instanceTimeMs = instance.getAlarmTime().getTimeInMillis();
            final long now = System.currentTimeMillis();

            // Sanity check: ensure we are actually at or past the alarm time.
            if (now < instanceTimeMs - FALLBACK_GRACE_MS) {
                LogUtils.i("NightWatch fallback: still %.1f min before alarm — rescheduling",
                        (instanceTimeMs - now) / 60_000.0);
                // Reschedule for the actual alarm.
                schedulePollAndFallback(instanceTimeMs);
                return;
            }

            // Guard: only fire if the instance is not already firing/fired/missed/dismissed.
            if (instance.mAlarmState >= AlarmInstance.FIRED_STATE) {
                LogUtils.i("NightWatch fallback: instance %d already in state %d — no fallback needed",
                        instance.mId, instance.mAlarmState);
                stopSelf();
                return;
            }

            LogUtils.i("NightWatch fallback: firing alarm for instance %d (state=%d)",
                    instance.mId, instance.mAlarmState);

            // Send the exact same intent AlarmManager would send: CHANGE_STATE_ACTION → FIRED_STATE,
            // directed at AlarmService which then calls AlarmStateManager.handleIntent().
            // Note: AlarmStateManager.ALARM_MANAGER_TAG is private; use the string value directly.
            final Intent fireIntent = AlarmStateManager.createStateChangeIntent(
                    ctx,
                    instance,
                    "ALARM_MANAGER",
                    AlarmInstance.FIRED_STATE,
                    SettingsDAO.getGlobalIntentId(prefs));
            fireIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.startService(fireIntent);

            // Give AlarmService a moment to start and stop the service after a short delay.
            // Cast to Runnable to resolve ambiguity with Callable overload.
            mScheduler.schedule((Runnable) this::stopSelf, 30, TimeUnit.SECONDS);

        } catch (Exception e) {
            LogUtils.e("NightWatch: exception in maybeFallbackFireAlarm", e);
        }
    }

    // ──────────────────────────────────────────────────────────
    // Notification update
    // ──────────────────────────────────────────────────────────

    private void updateNotification(long targetAlarmTimeMs) {
        final Notification notification = buildNotification(
                getApplicationContext(), targetAlarmTimeMs, SettingsDAO.getLanguageCode(mPrefs));
        // Re-post via ServiceCompat; on older APIs this is a no-op but safe.
        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted on API 33+ — no crash.
        }
    }

    // ──────────────────────────────────────────────────────────
    // Debug hook (BuildConfig.DEBUG only)
    // ──────────────────────────────────────────────────────────

    /**
     * Cancels the AlarmManager entry for the next alarm instance WITHOUT stopping this service.
     * Call this in debug builds to verify that the Night Watch fallback fires the alarm by itself.
     *
     * <p>Trigger from adb:
     * <pre>
     *   adb shell am startservice -a com.best.deskclock.NIGHT_WATCH_DEBUG_CANCEL_ALARM \
     *       com.best.deskclock.debug/com.best.deskclock.alarms.NightWatchService
     * </pre>
     * Or from the Night Watch settings screen debug item (visible only in debug builds).</p>
     */
    private void handleDebugCancelAlarmManager() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        LogUtils.i("NightWatch DEBUG: cancelling AlarmManager entries for next alarm");

        final Context ctx = getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(ctx);
        final AlarmInstance instance = AlarmInstance.getNextFiringAlarm(ctx);

        if (instance == null) {
            LogUtils.w("NightWatch DEBUG: no next alarm to cancel");
            return;
        }

        // Cancel the PendingIntent that AlarmManager would fire.
        // Note: AlarmStateManager.ALARM_MANAGER_TAG is private; use the string value directly.
        final Intent intent = AlarmStateManager.createStateChangeIntent(
                ctx, instance, "ALARM_MANAGER",
                AlarmInstance.FIRED_STATE, SettingsDAO.getGlobalIntentId(prefs));
        final PendingIntent pi = PendingIntent.getService(
                ctx, instance.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            final AlarmManager am = ctx.getSystemService(AlarmManager.class);
            am.cancel(pi);
            pi.cancel();
            LogUtils.i("NightWatch DEBUG: AlarmManager entry cancelled for instance %d", instance.mId);
        } else {
            LogUtils.w("NightWatch DEBUG: no AlarmManager PendingIntent found for instance %d",
                    instance.mId);
        }
    }

    // ──────────────────────────────────────────────────────────
    // Static helpers used by UI (AlarmFragment / NightWatchSettingsActivity)
    // ──────────────────────────────────────────────────────────

    /**
     * Returns true if Night Watch is currently armed (service is running and preference is set).
     */
    public static boolean isArmed(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_NIGHT_WATCH_ENABLED, false);
    }

    /**
     * Arm Night Watch: computes the next alarm time and starts the service.
     * Must be called from the UI (foreground) context.
     *
     * @param context calling Activity or Fragment context
     * @return {@code true} if the service was started, {@code false} if there is no upcoming alarm.
     */
    public static boolean arm(@NonNull Context context) {
        final AlarmInstance nextAlarm =
                AlarmInstance.getNextFiringAlarm(context.getApplicationContext());
        if (nextAlarm == null) {
            return false;
        }
        final long targetMs = nextAlarm.getAlarmTime().getTimeInMillis();
        final Intent intent = new Intent(context, NightWatchService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET_ALARM_TIME, targetMs);
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    /**
     * Disarm Night Watch: stops the service.
     */
    public static void disarm(@NonNull Context context) {
        final Intent intent = new Intent(context, NightWatchService.class)
                .setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * Triggers the debug AlarmManager cancellation hook (debug builds only).
     */
    public static void debugCancelAlarmManager(@NonNull Context context) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        final Intent intent = new Intent(context, NightWatchService.class)
                .setAction(ACTION_DEBUG_CANCEL_ALARM_MANAGER);
        context.startService(intent);
    }
}
