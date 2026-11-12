package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Notifications without Firebase. Telegram pushes through Firebase (FCM) only to apps whose Firebase key it holds;
 * a build without the owner's Firebase config (the placeholder one) gets no pushes at all. Such a build keeps
 * Telegram's own background connection open instead, in a quiet foreground service with a minimal notification,
 * the way open-source Telegram builds do, so messages, calls and mentions are still notified.
 * The person can still turn it off in Settings → Notifications (Keep-Alive Service, Background connection).
 */
public final class TigiPush {

    private static final String PLACEHOLDER_PROJECT = "tigi-placeholder";
    private static final String CHANNEL_ID = "nilak_connection";
    private static final int NOTIFICATION_ID = 0x4E494C;

    private static Boolean needsConnection;

    private TigiPush() {
    }

    /** True when this build cannot receive Firebase pushes, so the app has to stay connected itself. */
    public static boolean needsConnection() {
        if (needsConnection == null) {
            String project = null;
            try {
                final Context context = ApplicationLoader.applicationContext;
                final int id = context.getResources().getIdentifier("project_id", "string", context.getPackageName());
                project = id != 0 ? context.getString(id) : null;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            needsConnection = project == null || project.startsWith(PLACEHOLDER_PROJECT);
        }
        return needsConnection;
    }

    /** Called by NotificationsService when it starts: becomes a foreground service when the app keeps itself connected. */
    public static void startForeground(Service service) {
        if (!needsConnection()) {
            return;
        }
        try {
            final NotificationManager manager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    LocaleController.getString(R.string.TigiConnectionChannel), NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            final Intent open = new Intent(service, LaunchActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent content = PendingIntent.getActivity(service, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            final Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.TigiConnectionTitle))
                .setContentText(LocaleController.getString(R.string.TigiConnectionText))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(content)
                .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                service.startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            // Android refused (started from the background): the plain service still runs while it can
            FileLog.e(e);
        }
    }

    /** Starts NotificationsService the way Android requires for a service that becomes a foreground service. */
    public static void startService(Context context) {
        final Intent intent = new Intent(context, NotificationsService.class);
        if (needsConnection() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(intent);
                return;
            } catch (Throwable e) {
                // not allowed from the background on newer Android; the plain service below runs while it can
                FileLog.e(e);
            }
        }
        context.startService(intent);
    }
}
