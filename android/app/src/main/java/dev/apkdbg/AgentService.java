package dev.apkdbg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * A foreground service that only exists while the agent is answering, so the
 * process (and its prime-agent child) isn't killed when the user switches apps
 * or the screen goes off mid-turn.
 */
public class AgentService extends Service implements Agent.Listener {
    private static final String CHANNEL = "agent";
    private PowerManager.WakeLock wake;
    private boolean foreground = false;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, AgentService.class));
        } catch (RuntimeException e) { // background-start limits; the turn still runs
            Log.w(Agent.TAG, "foreground service", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent at work", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Checking your phone")
                .setContentText("The agent is working on your question.")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        foreground = true;
        Agent.get(this).addListener(this);
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apkdbg:agent");
            wake.acquire(30 * 60 * 1000L);
        }
        changed();
        return START_NOT_STICKY;
    }

    /** Stop once the agent is idle. Calling stopService() from outside could beat
     * startForeground() and crash the app, so the service stops itself. */
    @Override
    public void changed() {
        if (foreground && !Agent.get(this).isRunning()) stopSelf();
    }

    @Override
    public void onDestroy() {
        Agent.get(this).removeListener(this);
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
