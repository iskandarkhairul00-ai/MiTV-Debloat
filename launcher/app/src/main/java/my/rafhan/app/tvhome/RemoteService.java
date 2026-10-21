package my.rafhan.app.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the phone remote's HTTP server alive while other apps are on screen. Started by
 * MainActivity (the launcher is started at boot, so no boot receiver is needed).
 */
public class RemoteService extends Service {
    private static final String CHANNEL = "remote";
    private RemoteServer server;
    private NetBanner netBanner;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Phone remote")
                .setContentText("Port " + RemoteServer.PORT)
                .build();
        startForeground(1, n);
        server = new RemoteServer(this);
        server.start();
        netBanner = new NetBanner(this);
        netBanner.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && server != null) server.trim();
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (netBanner != null) netBanner.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
