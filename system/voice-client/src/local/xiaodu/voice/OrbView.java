package local.xiaodu.voice;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Procedural gradients and actual audio envelopes. Render only while visible. */
final class OrbView extends View {
    boolean visible=true,preview=false;
    private final Paint paint=new Paint(3);
    private final Path shape=new Path();
    private float envelope;
    OrbView(Context c){super(c);setContentDescription("贾维斯实时语音，蓝色光球随声音变化");}
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(),h=getHeight(),cx=w*.5f,cy=h*.43f;
        float t=android.os.SystemClock.uptimeMillis()*.001f;
        boolean active=CallService.active;
        float measured=preview?.23f:Math.max(CallService.inputLevel,CallService.outputLevel);
        envelope+=(measured-envelope)*.18f;
        float r=Math.min(w*.18f,h*.21f)*(1+Math.min(.32f,envelope*.65f));
        paint.setShader(new RadialGradient(cx,cy,r*2.1f,new int[]{0x38277cff,0x102959c7,0x00080d19},null,Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,r*2.1f,paint);
        shape.reset();
        for(int i=0;i<=100;i++) {
            double a=i*Math.PI*2/100;
            float wave=(float)(Math.sin(a*3+t*.85)*.025+Math.sin(a*5-t*.65)*.017)*(active||preview?1: .35f);
            float rr=r*(1+wave*(1+envelope*2));
            float x=cx+(float)Math.cos(a)*rr,y=cy+(float)Math.sin(a)*rr;
            if(i==0)shape.moveTo(x,y);else shape.lineTo(x,y);
        }
        shape.close();
        paint.setShader(new RadialGradient(cx-r*.3f+(float)Math.sin(t*.4)*r*.15f,cy-r*.35f,r*1.8f,
            new int[]{0xffe8fbff,0xffa8e5ff,0xff409cff,0xff5863da},new float[]{0,.32f,.7f,1},Shader.TileMode.CLAMP));
        c.drawPath(shape,paint);
        paint.setShader(null);paint.setColor(0x60ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f);c.drawPath(shape,paint);paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xffe5edf8);paint.setTextSize(h*.046f);c.drawText("贾维斯",cx,h*.10f,paint);
        String status=preview?"正在聆听":CallService.label;
        paint.setTextSize(h*.041f);paint.setColor(0xffdce9fa);c.drawText(status,cx,h*.73f,paint);
        paint.setTextSize(h*.028f);paint.setColor(0xff7991b2);
        c.drawText(active?(CallService.muted?"麦克风已静音":"可以连续说话，也可以随时打断我"):"叫一声「贾维斯」，开始对话",cx,h*.79f,paint);
        if(visible)postInvalidateDelayed(33);
    }
}
