package local.xiaodu.voice;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** 一次填写局域网地址；不登录、不配对、不读取主机凭据。 */
public final class ConnectionSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,24,32,24);
        TextView heading=new TextView(this);heading.setText("桥接主机地址");heading.setTextSize(24);root.addView(heading);
        TextView note=new TextView(this);note.setText("填写运行 Codex 桥接服务的电脑地址，例如 http://你的电脑IP:8766。设备与电脑应在同一可信局域网。");root.addView(note);
        EditText address=new EditText(this);address.setSingleLine(true);address.setHint("http://桥接主机:8766");address.setText(ConnectionSettings.load(this));root.addView(address);
        Button save=new Button(this);save.setText("保存");root.addView(save);
        save.setOnClickListener(v -> {try{ConnectionSettings.save(this,address.getText().toString());Toast.makeText(this,"已保存，下次通话生效",Toast.LENGTH_SHORT).show();finish();}catch(Exception error){address.setError(error.getMessage());}});
        setContentView(root);
    }
}
