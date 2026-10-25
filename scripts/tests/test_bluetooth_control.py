"""Exercise the production controller with a small event-driven Android fake.

These tests validate command dispatch, actual-state acknowledgements and receiver
cleanup. Hardware/permission/timeout behaviour still needs device validation.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
FAKES={
'android/content/Intent.java':'package android.content; public class Intent {}',
'android/content/ContentResolver.java':'package android.content; public class ContentResolver {}',
'android/content/IntentFilter.java':'package android.content; public class IntentFilter {public IntentFilter(String s){} public void addAction(String s){}}',
'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Context.java':'''package android.content;
public class Context {
 public int registered,unregistered; public static Context current; public BroadcastReceiver receiver;
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){registered++;receiver=r;current=this;return null;}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver!=r)throw new AssertionError();unregistered++;receiver=null;}
 public static void changed(){if(current!=null&&current.receiver!=null)current.receiver.onReceive(current,new Intent());}
}''',
'android/os/Build.java':'package android.os; public class Build {public static class VERSION {public static int SDK_INT=27;}}',
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long offset;public static long elapsedRealtime(){return System.nanoTime()/1000000+offset;}}',
'android/provider/Settings.java':'package android.provider; public class Settings {public static class Global {public static int mesh;public static int getInt(android.content.ContentResolver c,String s,int d){return mesh;}}}',
'org/json/JSONObject.java':'''package org.json;
public class JSONObject {
 public static final Object NULL=new Object();private java.util.Map<String,Object> fields=new java.util.HashMap<>();
 public JSONObject put(String k,Object v){fields.put(k,v);return this;}
 public Object get(String k){return fields.get(k);}
 public boolean has(String k){return fields.containsKey(k);}
 public String getString(String k){return (String)fields.get(k);}
 public java.util.Iterator<String> keys(){return fields.keySet().iterator();}
}''',
'android/bluetooth/BluetoothAdapter.java':'''package android.bluetooth;
public class BluetoothAdapter {
 public static final int STATE_OFF=10,STATE_TURNING_ON=11,STATE_ON=12,STATE_TURNING_OFF=13,SCAN_MODE_CONNECTABLE_DISCOVERABLE=23;
 public static final String ACTION_STATE_CHANGED="state",ACTION_SCAN_MODE_CHANGED="scan";
 public static BluetoothAdapter current;
 public volatile int state=STATE_OFF,mode=20;public int enables,disables,pairs;
 public boolean fail,throwPower,timeout,async;
 public static BluetoothAdapter getDefaultAdapter(){return current;}
 public boolean isEnabled(){return state==STATE_ON;}public int getState(){return state;}
 public int getScanMode(){return mode;}public String getName(){return "Test speaker";}
 private boolean power(int target){
  if(throwPower)throw new SecurityException();if(fail)return false;
  if(timeout){android.os.SystemClock.offset+=9000;return true;}
  Runnable finish=()->{state=target;android.content.Context.changed();};
  if(async){new Thread(()->{try{Thread.sleep(30);}catch(Exception e){}finish.run();}).start();}else finish.run();
  return true;
 }
 public boolean enable(){enables++;return power(STATE_ON);}public boolean disable(){disables++;return power(STATE_OFF);}
 public boolean setScanMode(int m){pairs++;if(fail)return false;mode=m;android.content.Context.changed();return true;}
}''',
}


class BluetoothControlTests(unittest.TestCase):
    def test_actual_controller_state_transitions_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);sources=[]
            for name,text in FAKES.items():
                p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);sources.append(str(p))
            harness=folder/'BluetoothControlTest.java';harness.write_text((ROOT/'scripts/tests/BluetoothControlTest.java').read_text())
            sources.extend([str(harness),str(ROOT/'system/speech-http/src/local/xiaodu/speech/BluetoothControl.java')])
            subprocess.run(['javac','--release','8','-d',str(folder),*sources],capture_output=True,text=True,check=True)
            result=subprocess.run(['java','-cp',str(folder),'local.xiaodu.speech.BluetoothControlTest'],capture_output=True,text=True,check=True,timeout=8)
            self.assertIn('14 scenarios passed',result.stdout)


if __name__=='__main__':unittest.main()
