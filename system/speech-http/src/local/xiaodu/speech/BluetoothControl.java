package local.xiaodu.speech;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.Iterator;
import org.json.JSONObject;

/** One-shot adapter control on the owned Android 8.1 device; no discovery scan. */
final class BluetoothControl {
    private static final long TIMEOUT_MS=8000;
    static final class Failure extends Exception {
        final int status;final String code;
        Failure(int status,String code){this.status=status;this.code=code;}
    }
    private static String stateName(int state) {
        switch(state) {
            case BluetoothAdapter.STATE_ON:return "on";
            case BluetoothAdapter.STATE_OFF:return "off";
            case BluetoothAdapter.STATE_TURNING_ON:return "turning_on";
            case BluetoothAdapter.STATE_TURNING_OFF:return "turning_off";
            default:return "unavailable";
        }
    }
    private static JSONObject snapshot(BluetoothAdapter adapter) throws Exception {
        return new JSONObject().put("enabled",adapter.isEnabled())
            .put("state",stateName(adapter.getState()))
            .put("name",adapter.getName()==null?JSONObject.NULL:adapter.getName())
            .put("discoverable",adapter.getScanMode()==BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
    }
    static JSONObject request(Context context,JSONObject body) throws Exception {
        Iterator<String> keys=body.keys();
        while(keys.hasNext())if(!"action".equals(keys.next()))throw new Failure(400,"unknown_argument");
        if(body.has("action")&&!(body.get("action") instanceof String))throw new Failure(400,"invalid_bluetooth_action");
        return execute(context,body.has("action")?body.getString("action"):"");
    }
    static synchronized JSONObject execute(Context context,String action) throws Exception {
        if(!action.isEmpty()&&!"on".equals(action)&&!"off".equals(action)&&!"pair".equals(action))
            throw new Failure(400,"invalid_bluetooth_action");
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null)throw new Failure(409,"bluetooth_unavailable");
        if(action.isEmpty())return snapshot(adapter);
        Method pairing=null;
        if("pair".equals(action)) {
            if(android.os.Build.VERSION.SDK_INT!=27)throw new Failure(409,"bluetooth_pairing_unsupported");
            try {pairing=adapter.getClass().getMethod("setScanMode",int.class);}
            catch(NoSuchMethodException absent){throw new Failure(409,"bluetooth_pairing_unsupported");}
        }
        int state=adapter.getState(),target="off".equals(action)?BluetoothAdapter.STATE_OFF:BluetoothAdapter.STATE_ON;
        if(!"pair".equals(action)&&state==target)
            return snapshot(adapter).put("action",action).put("sent",false).put("verified",true);
        if(state!=BluetoothAdapter.STATE_ON&&state!=BluetoothAdapter.STATE_OFF)
            throw new Failure(409,"bluetooth_busy");
        // The factory preserves Mesh when disabling classic Bluetooth. Do not
        // turn off that shared radio through this narrower adapter API.
        if("off".equals(action)&&Settings.Global.getInt(context.getContentResolver(),"BleMeshEnable",0)!=0)
            throw new Failure(409,"bluetooth_mesh_active");
        final Object changed=new Object();
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){synchronized(changed){changed.notifyAll();}}
        };
        IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        context.registerReceiver(receiver,filter);
        boolean sent=false;
        long deadline=SystemClock.elapsedRealtime()+TIMEOUT_MS;
        try {
            synchronized(changed) {
                int current=adapter.getState();
                if(current!=BluetoothAdapter.STATE_ON&&current!=BluetoothAdapter.STATE_OFF)
                    throw new Failure(409,"bluetooth_busy");
                if(adapter.getState()!=target) {
                    sent=true;
                    boolean accepted=target==BluetoothAdapter.STATE_ON?adapter.enable():adapter.disable();
                    if(!accepted&&adapter.getState()!=target)
                        return snapshot(adapter).put("action",action).put("sent",true).put("verified",false);
                    while(adapter.getState()!=target) {
                        long left=deadline-SystemClock.elapsedRealtime();if(left<=0)break;
                        changed.wait(left);
                    }
                }
                if(adapter.getState()!=target)
                    return snapshot(adapter).put("action",action).put("sent",sent).put("verified",false);
                if("pair".equals(action)) {
                    // Same Android system method family used by the factory
                    // BlueToothHelper; hidden API, deliberately Android-8.1-only.
                    boolean accepted=(Boolean)pairing.invoke(adapter,BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                    sent=true;
                    while(accepted&&adapter.getScanMode()!=BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                        long left=deadline-SystemClock.elapsedRealtime();if(left<=0)break;
                        changed.wait(left);
                    }
                    return snapshot(adapter).put("action",action).put("sent",true)
                        .put("verified",accepted&&adapter.getScanMode()==BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                }
                return snapshot(adapter).put("action",action).put("sent",sent).put("verified",true);
            }
        }finally{context.unregisterReceiver(receiver);}
    }
}
