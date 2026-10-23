package local.xiaodu.screencare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;

/** Keeps the chosen Android dream selected when the vendor resets it at startup.
 * The original device timer handles the owner-configured night screen-off policy independently.
 * Observe only saved daytime settings, never fight its 60-second night timeout.
 */
public final class PolicyService extends Service {
    public static final String DREAM = "local.xiaodu.screencare/.ClockDream";
    public static final String STOCK_DREAM = "com.baidu.launcher/com.baidu.duer.wallpaper.screensaver.GeneralDreamService";
    private final Handler handler = new Handler();
    private String error = "";
    private int changes = 0;
    private final Runnable apply = () -> enforce();
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange, Uri uri) {
            handler.removeCallbacks(apply);
            handler.postDelayed(apply, 250);
        }
    };

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", true);
    }

    public static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("policy", MODE_PRIVATE);
    }

    public static void start(Context context) {
        if (enabled(context)) context.startForegroundService(new Intent(context, PolicyService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        // Keep a small private startup record across the runtime's zygote reload.
        // This distinguishes device boot startup from later host-assisted loading.
        String boot = "unknown";
        try (BufferedReader input = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            boot = input.readLine();
        } catch (Exception ignored) { }
        String audit = "boot=" + boot + " elapsed=" + SystemClock.elapsedRealtime()
                + " magisk=" + new File("/sbin/.magisk").exists();
        String history = prefs(this).getString("startup_audit", "");
        String[] lines = history.split("\n");
        StringBuilder keep = new StringBuilder();
        for (int i = Math.max(0, lines.length - 5); i < lines.length; i++) {
            if (!lines[i].isEmpty()) keep.append(lines[i]).append('\n');
        }
        keep.append(audit);
        prefs(this).edit().putString("startup_audit", keep.toString()).apply();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel("clock", "待机时钟", NotificationManager.IMPORTANCE_MIN);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1, new Notification.Builder(this, "clock")
                .setSmallIcon(R.drawable.ic_clock).setContentTitle("待机时钟已开启")
                .setContentText("白天简洁时钟，夜间自动熄屏")
                .setContentIntent(open).setOngoing(true).build());
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor("screensaver_components"), false, observer);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor("screensaver_enabled"), false, observer);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("screen_saver_off_timeout"), false, observer);
    }

    private void secure(String key, String value) {
        if (!value.equals(Settings.Secure.getString(getContentResolver(), key))) {
            if (!Settings.Secure.putString(getContentResolver(), key, value)) throw new IllegalStateException(key);
            changes++;
        }
    }

    private void enforce() {
        if (!enabled(this)) { stopSelf(); return; }
        try {
            secure("screensaver_components", DREAM);
            secure("screensaver_enabled", "1");
            secure("screensaver_activate_on_sleep", "1");
            if (Settings.Global.getInt(getContentResolver(), "screen_saver_off_timeout", -1) != 120000) {
                if (!Settings.Global.putInt(getContentResolver(), "screen_saver_off_timeout", 120000))
                    throw new IllegalStateException("daytime timeout");
                changes++;
            }
            error = "";
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
            Log.e("XiaoduScreenCare", "Could not apply screen settings", e);
        }
        prefs(this).edit().putString("last_error", error).apply();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        enforce();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(apply);
        getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("enabled=" + enabled(this));
        writer.println("selectedDream=" + Settings.Secure.getString(getContentResolver(), "screensaver_components"));
        writer.println("savedDayTimeout=" + Settings.Global.getInt(getContentResolver(), "screen_saver_off_timeout", -1));
        writer.println("currentTimeout=" + Settings.System.getInt(getContentResolver(), "screen_off_timeout", -1));
        writer.println("nightScreenOff=" + Settings.Global.getInt(getContentResolver(), "duershow_is_dream", -1));
        writer.println("changes=" + changes);
        writer.println("error=" + error);
        writer.println(prefs(this).getString("startup_audit", ""));
    }
}
