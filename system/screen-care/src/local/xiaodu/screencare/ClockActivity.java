package local.xiaodu.screencare;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

public final class ClockActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ClockView clock = new ClockView(this, true);
        clock.setOnClickListener(v -> finish());
        clock.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
        });
        setContentView(clock);
        PolicyService.start(this);
    }
}
