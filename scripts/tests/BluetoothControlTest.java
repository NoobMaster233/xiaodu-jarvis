package local.xiaodu.speech;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import org.json.JSONObject;

public final class BluetoothControlTest {
    static BluetoothAdapter adapter;static Context context;
    static void reset(int state){
        adapter=new BluetoothAdapter();adapter.state=state;BluetoothAdapter.current=adapter;context=new Context();
        android.os.Build.VERSION.SDK_INT=27;android.provider.Settings.Global.mesh=0;
        android.os.SystemClock.offset=0;Context.current=null;
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void cleaned(){check(context.registered==1&&context.unregistered==1&&context.receiver==null);}
    static void verified(JSONObject r,boolean expected){check(Boolean.valueOf(expected).equals(r.get("verified")));}
    static void rejected(String action,int status)throws Exception{
        try{BluetoothControl.execute(context,action);throw new AssertionError();}
        catch(BluetoothControl.Failure e){check(e.status==status);}
        check(adapter.enables==0&&adapter.disables==0&&adapter.pairs==0&&context.registered==0);
    }
    public static void main(String[] args)throws Exception{
        reset(12);BluetoothControl.execute(context,"");check(adapter.enables==0&&context.registered==0);
        reset(12);verified(BluetoothControl.execute(context,"on"),true);check(adapter.enables==0&&context.registered==0);
        reset(10);verified(BluetoothControl.execute(context,"off"),true);check(adapter.disables==0&&context.registered==0);
        reset(10);verified(BluetoothControl.execute(context,"on"),true);check(adapter.enables==1);cleaned();
        reset(10);adapter.async=true;verified(BluetoothControl.execute(context,"on"),true);check(adapter.enables==1);cleaned();
        reset(12);verified(BluetoothControl.execute(context,"off"),true);check(adapter.disables==1);cleaned();
        reset(10);verified(BluetoothControl.execute(context,"pair"),true);check(adapter.enables==1&&adapter.pairs==1);cleaned();
        reset(12);verified(BluetoothControl.execute(context,"pair"),true);check(adapter.enables==0&&adapter.pairs==1);cleaned();
        reset(11);rejected("on",409);
        reset(12);android.provider.Settings.Global.mesh=1;rejected("off",409);
        reset(10);android.os.Build.VERSION.SDK_INT=28;rejected("pair",409);
        reset(12);adapter.fail=true;verified(BluetoothControl.execute(context,"pair"),false);cleaned();
        reset(10);adapter.throwPower=true;try{BluetoothControl.execute(context,"on");throw new AssertionError();}catch(SecurityException expected){}cleaned();
        reset(10);adapter.timeout=true;verified(BluetoothControl.execute(context,"on"),false);check(adapter.enables==1);cleaned();
        System.out.println("14 scenarios passed");
    }
}
