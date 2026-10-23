package local.xiaodu.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

/** A small native audio surface; no WebView, account UI, or credential files. */
public final class CallActivity extends Activity {
    private OrbView orb;
    private Button startButton,muteButton,endButton;
    private boolean diagnostic;
    private boolean hadCall=false;
    private boolean resumed=false;
    private long timingActivityNs;
    private boolean timingNewIntent;
    private final android.os.Handler ui=new android.os.Handler();
    private final Runnable check=new Runnable(){public void run(){
        if(startButton!=null){boolean show=CallService.active||orb.preview;
            startButton.setVisibility(show?View.GONE:View.VISIBLE);muteButton.setVisibility(show?View.VISIBLE:View.GONE);endButton.setVisibility(show?View.VISIBLE:View.GONE);
        }
        if(hadCall&&!CallService.active){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);hadCall=false;
            if(!orb.preview)ui.postDelayed(CallActivity.this::finish,1200);
        }
        if(resumed||hadCall||CallService.active)ui.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        timingActivityNs=android.os.SystemClock.elapsedRealtimeNanos();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff080d19);
        orb=new OrbView(this);root.addView(orb,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(android.view.Gravity.CENTER);
        Button mute=new Button(this);mute.setText("静音");mute.setContentDescription("静音麦克风");
        mute.setOnClickListener(v -> {CallService.toggleMute();mute.setText(CallService.muted?"取消静音":"静音");});
        Button end=new Button(this);end.setText("结束通话");end.setContentDescription("结束通话");
        end.setOnClickListener(v -> {startService(new Intent(this,CallService.class).setAction("end"));finish();});
        Button start=new Button(this);start.setText("开始通话");start.setContentDescription("开始通话");
        startButton=start;muteButton=mute;endButton=end;
        start.setOnClickListener(v -> connect(getIntent()));
        Button settings=new Button(this);settings.setText("连接设置");settings.setOnClickListener(v -> startActivity(new Intent(this,ConnectionSettingsActivity.class)));
        for(Button b:new Button[]{start,mute,end,settings}) {
            android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
            bg.setColor(b==end?0xff9a3648:b==start?0xff2768b6:0xff202d43);bg.setCornerRadius(28);
            b.setBackground(bg);b.setTextColor(0xffedf3ff);b.setAllCaps(false);b.setTextSize(15);b.setElevation(0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(145,52);lp.setMargins(12,0,12,0);buttons.addView(b,lp);
        }
        FrameLayout.LayoutParams controls=new FrameLayout.LayoutParams(-1,76,android.view.Gravity.BOTTOM);controls.bottomMargin=12;
        root.addView(buttons,controls);setContentView(root);
        consume(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);timingActivityNs=android.os.SystemClock.elapsedRealtimeNanos();timingNewIntent=true;setIntent(intent);consume(intent);}
    private void consume(Intent i) {
        diagnostic=i.getBooleanExtra("diagnostic",false);
        orb.preview=i.getBooleanExtra("preview",false);
        if(i.getBooleanExtra("wake",false) || diagnostic)connect(i);
    }
    private void connect(Intent from) {
        if(ConnectionSettings.load(this).isEmpty())startActivity(new Intent(this,ConnectionSettingsActivity.class));
        hadCall=true;
        Intent i=new Intent(this,CallService.class).setAction("start");
        i.putExtra("no_mic",from.getBooleanExtra("no_mic",false));
        i.putExtra("silent",from.getBooleanExtra("silent",false));
        i.putExtra("diagnostic",diagnostic);
        String id=from.getStringExtra("call_id");if(id!=null)i.putExtra("call_id",id);
        i.putExtra("timing_activity_ns",timingActivityNs);i.putExtra("timing_new_intent",timingNewIntent);
        i.putExtra("timing_kws_accepted_ns",from.getLongExtra("timing_kws_accepted_ns",0));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startForegroundService(i);
    }
    @Override public void onBackPressed(){startService(new Intent(this,CallService.class).setAction("end"));super.onBackPressed();}
    @Override protected void onResume(){
        super.onResume();resumed=true;orb.visible=true;orb.invalidate();
        sendBroadcast(new Intent("local.xiaodu.screencare.SHOW_VOICE").setPackage("local.xiaodu.screencare"));
        ui.removeCallbacks(check);ui.postDelayed(check,500);
    }
    @Override protected void onPause(){orb.visible=false;resumed=false;super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);super.onDestroy();}
}
