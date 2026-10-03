package com.hippo.ehviewer.smb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.storage.NetworkStorage;

/** Started and stopped by {@link SmbDirectDownloader}, which owns all the state. */
public final class SmbDownloadService extends Service {

    private static final String TAG = "SmbDownloadService";
    // The upstream DownloadService's channel, shared by both pipelines.
    private String channelId() {
        return getPackageName() + ".download";
    }
    private static final int NOTIFICATION_ID = 0x536D6244;

    public static final String ACTION_STOP = "com.hippo.ehviewer.smb.STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, SmbDownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, SmbDownloadService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignore) {
            // Service already stopped or cannot start in background — fine.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startInForeground(buildNotification(getString(R.string.smb_download_notif_title,
                        NetworkStorage.active().displayName()),
                getString(R.string.smb_download_notif_preparing), 0, 0, true));
        SmbDirectDownloader.getInstance().attachService(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        // Make sure the foreground notification is up even on re-start.
        startInForeground(buildNotification(getString(R.string.smb_download_notif_title,
                        NetworkStorage.active().displayName()),
                getString(R.string.smb_download_notif_preparing), 0, 0, true));
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        SmbDirectDownloader.getInstance().pauseAll();
        stopForegroundCompat();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        SmbDirectDownloader.getInstance().detachService();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void updateNotification(String title, String text, int max, int progress, boolean indeterminate) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(title, text, max, progress, indeterminate));
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to update SMB notification", e);
        }
    }

    private void startInForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "startForeground refused", e);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private Notification buildNotification(String title, String text, int max, int progress, boolean indeterminate) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId())
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        // Always a bar: OEM shades hide notifications without one in their silent group.
        if (max > 0) {
            b.setProgress(max, progress, indeterminate);
            b.setContentInfo(progress + "/" + max);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(channelId()) == null) {
                NotificationChannel channel = new NotificationChannel(
                        channelId(),
                        getString(R.string.download_service),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }
        }
    }
}
