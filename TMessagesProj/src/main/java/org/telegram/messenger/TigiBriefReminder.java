package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

import java.util.Calendar;

/**
 * Nilak: one notification a day, at the hour the user picked (8:00 by default), saying the daily brief is ready.
 * Skipped on days the brief was already opened. Tapping it opens the app on the brief. Uses an inexact daily
 * alarm, so the phone may deliver it a little late to save battery; it is set again each time the app opens.
 */
public class TigiBriefReminder extends BroadcastReceiver {

    public static final String ACTION_OPEN = "org.telegram.nilak.OPEN_BRIEF";
    private static final String ACTION_FIRE = "org.telegram.nilak.BRIEF_REMINDER";
    private static final String CHANNEL = "nilak_brief";
    private static final int NOTIFICATION_ID = 0x4e1b;
    private static final String KEY_ENABLED = "nilak_brief_reminder";
    private static final String KEY_HOUR = "nilak_brief_hour";
    private static final String KEY_LAST_OPEN = "nilak_brief_last_open_day";

    /** Set when the app was opened from the notification; Home opens the brief once. */
    public static volatile boolean pendingOpen;

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static int hour() {
        return prefs().getInt(KEY_HOUR, 8);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        schedule(ApplicationLoader.applicationContext);
    }

    public static void setHour(int hour) {
        prefs().edit().putInt(KEY_HOUR, Math.max(0, Math.min(23, hour))).apply();
        schedule(ApplicationLoader.applicationContext);
    }

    /** The brief was opened today: no reminder until tomorrow. */
    public static void markOpenedToday() {
        prefs().edit().putInt(KEY_LAST_OPEN, today()).apply();
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        } catch (Exception ignore) {
        }
    }

    private static int today() {
        final Calendar now = Calendar.getInstance();
        return now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
    }

    private static PendingIntent firePending(Context context) {
        final Intent intent = new Intent(context, TigiBriefReminder.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets (or clears) the daily alarm; safe to call often. */
    public static void schedule(Context context) {
        if (context == null || !BuildVars.TIGI_HOME) {
            return;
        }
        try {
            final AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                return;
            }
            final PendingIntent pending = firePending(context);
            alarms.cancel(pending);
            if (!isEnabled()) {
                return;
            }
            final Calendar next = Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY, hour());
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            if (next.getTimeInMillis() <= System.currentTimeMillis()) {
                next.add(Calendar.DAY_OF_YEAR, 1);
            }
            alarms.setInexactRepeating(AlarmManager.RTC, next.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pending);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_FIRE.equals(intent.getAction()) || !isEnabled()) {
            return;
        }
        if (prefs().getInt(KEY_LAST_OPEN, 0) == today()) {
            return;
        }
        try {
            final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                final NotificationChannel channel = new NotificationChannel(CHANNEL, LocaleController.getString(R.string.NilakBrief), NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            final Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setColor(0xFF7138D5)
                .setContentTitle(LocaleController.getString(R.string.NilakBriefReady))
                .setContentText(LocaleController.getString(R.string.NilakBriefReadyText))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
