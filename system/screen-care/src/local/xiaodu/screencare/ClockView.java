package local.xiaodu.screencare;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A minute-based clock. No network, speech engine, sensors, or wake locks. */
public final class ClockView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler();
    private final boolean showHint;
    private final Runnable tick = new Runnable() {
        public void run() {
            invalidate();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 20);
        }
    };

    public ClockView(Context context, boolean hint) {
        super(context);
        showHint = hint;
        setBackgroundColor(Color.rgb(7, 11, 17));
        setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | SYSTEM_UI_FLAG_IMMERSIVE_STICKY | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Date now = new Date();
        long minute = System.currentTimeMillis() / 60000;
        float scale = Math.min(getWidth() / 1024f, getHeight() / 600f);
        // Small, infrequent movement avoids keeping precisely the same pixels lit.
        float x = getWidth() / 2f + ((minute % 5) - 2) * 4 * scale;
        float y = getHeight() / 2f + (((minute / 5) % 5) - 2) * 3 * scale;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        paint.setTextSize(196 * scale);
        paint.setColor(Color.rgb(232, 238, 247));
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(now);
        canvas.drawText(time, x, y + 5 * scale, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(32 * scale);
        paint.setColor(Color.rgb(151, 165, 183));
        canvas.drawText(new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now),
                x, y + 75 * scale, paint);
        if (showHint) {
            paint.setTextSize(19 * scale);
            paint.setColor(Color.rgb(85, 101, 121));
            canvas.drawText("轻触返回  ·  长按设置", getWidth() / 2f, getHeight() - 35 * scale, paint);
        }
    }
}
