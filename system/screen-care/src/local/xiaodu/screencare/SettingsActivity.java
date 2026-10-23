package local.xiaodu.screencare;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view);
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); parent.addView(button);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(36), dp(18), dp(36), dp(16));
        root.setGravity(Gravity.CENTER_VERTICAL); root.setBackgroundColor(Color.rgb(16, 24, 36));
        setContentView(root);
        text(root, "屏幕与待机", 27, Color.WHITE);
        text(root, "无操作时显示时钟；夜间熄屏时间跟随系统设置", 19, 0xffb9c5d7);
        Switch toggle = new Switch(this); toggle.setText("启用简洁时钟"); toggle.setTextSize(20);
        toggle.setChecked(PolicyService.enabled(this)); root.addView(toggle);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            PolicyService.prefs(this).edit().putBoolean("enabled", checked).apply();
            if (checked) PolicyService.start(this);
            else {
                stopService(new Intent(this, PolicyService.class));
                Settings.Secure.putString(getContentResolver(), "screensaver_components", PolicyService.STOCK_DREAM);
            }
        });
        button(root, "预览时钟", () -> { startActivity(new Intent(this, ClockActivity.class)); finish(); });
        button(root, "返回桌面", () -> {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); finish();
        });
        String error = PolicyService.prefs(this).getString("last_error", "");
        if (!error.isEmpty()) text(root, "待机设置尚未生效，请检查设置权限", 16, 0xffe8ab86);
    }
}
