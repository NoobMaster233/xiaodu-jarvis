package local.xiaodu.screencare;

import android.service.dreams.DreamService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public final class ClockDream extends DreamService {
    // A dream has its own system window above ordinary activities. Let the
    // voice surface explicitly dismiss our dream instead of hiding behind it.
    private final BroadcastReceiver voiceWake=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){finish();}
    };
    @Override public void onCreate(){
        super.onCreate();
        registerReceiver(voiceWake,new IntentFilter("local.xiaodu.screencare.SHOW_VOICE"));
    }
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new ClockView(this, false));
    }
    @Override public void onDestroy(){unregisterReceiver(voiceWake);super.onDestroy();}
}
