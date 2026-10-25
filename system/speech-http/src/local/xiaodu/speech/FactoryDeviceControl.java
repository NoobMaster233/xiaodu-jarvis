package local.xiaodu.speech;

import android.app.Dialog;
import android.view.WindowManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.*;
import android.util.Base64;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Fixed factory camera/privacy and silent notice operations over the existing IPC. */
public final class FactoryDeviceControl implements InvocationHandler {
    public static final String METHOD="xiaodu_device_control_v1";
    private Dialog noticeDialog;
    private static FactoryDeviceControl instance;
    private final Context context;
    private final ClassLoader loader;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final class Failure extends Exception {
        final int code;final String reason;
        Failure(int code,String reason){this.code=code;this.reason=reason;}
    }
    private FactoryDeviceControl(Context context){this.context=context;loader=context.getClassLoader();}
    private Class<?> load(String name) throws Exception {return Class.forName(name,true,loader);}
    private Object singleton(String name) throws Exception {return load(name).getMethod("getInstance").invoke(null);}
    private static Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {
        return object.getClass().getMethod(name,types).invoke(object,args);
    }
    public static synchronized String start(Context context) throws Exception {
        if(!"com.baidu.launcher".equals(context.getPackageName())||Build.VERSION.SDK_INT!=27||
            context.getPackageManager().getPackageInfo("com.baidu.launcher",0).versionCode!=10682)
            throw new SecurityException("factory_device_host_required");
        if(instance!=null)return "already_running";
        FactoryDeviceControl candidate=new FactoryDeviceControl(context);
        Class<?> callback=candidate.load("com.baidu.duer.net.IRemoteRequest");
        Object proxy=Proxy.newProxyInstance(candidate.loader,new Class<?>[]{callback},candidate);
        Map<String,Object> methods=new HashMap<>();methods.put(METHOD,proxy);
        candidate.load("com.baidu.duer.net.RemoteReceiverManager").getMethod("register",Map.class).invoke(null,methods);
        instance=candidate;return "started";
    }
    private <T> T onMain(Callable<T> work) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return work.call();
        FutureTask<T> task=new FutureTask<>(work);
        if(!main.post(task))throw new Failure(503,"factory_host_unavailable");
        try{return task.get(2500,TimeUnit.MILLISECONDS);}
        finally{if(!task.isDone())task.cancel(false);}
    }
    private static void keys(JSONObject body,String... allowed) throws Exception {
        Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> it=body.keys();
        while(it.hasNext())if(!names.contains(it.next()))throw new Failure(400,"unknown_argument");
    }
    private boolean cameraDisabled() throws Exception {
        DevicePolicyManager policy=(DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if(policy==null)throw new Failure(503,"camera_policy_unavailable");
        return policy.getCameraDisabled(null);
    }
    private JSONObject camera(JSONObject body) throws Exception {
        keys(body,"enabled");
        if(!(body.opt("enabled") instanceof Boolean))throw new Failure(400,"invalid_camera_state");
        boolean enabled=body.getBoolean("enabled"),disabled=!enabled,before=cameraDisabled();
        if(before==disabled)return new JSONObject().put("enabled",!before).put("sent",false).put("verified",true);
        // Same privileged factory policy implementation; no toggles, settings clicks or shell intents.
        load("com.baidu.duershow.camera.CameraUtils").getMethod("disableCamera",boolean.class,boolean.class).invoke(null,disabled,true);
        boolean actual=cameraDisabled();
        return new JSONObject().put("enabled",!actual).put("sent",true).put("verified",actual==disabled);
    }
    private static String text(JSONObject body,String name,String fallback,int max) throws Exception {
        Object value=body.opt(name);
        if(value==null)value=fallback;
        if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max||((String)value).indexOf('\0')>=0)
            throw new Failure(400,"invalid_notice_text");
        return (String)value;
    }
    private JSONObject notice(JSONObject body) throws Exception {
        String action=body.optString("action","show");
        if(!"show".equals(action)&&!"clear".equals(action))throw new Failure(400,"invalid_notice_action");
        keys(body,"action","text","title");
        if("clear".equals(action)&&(body.has("text")||body.has("title")))throw new Failure(400,"invalid_notice_arguments");
        final boolean clear="clear".equals(action);
        final String content=clear?null:text(body,"text",null,500),title=clear?null:text(body,"title","贾维斯",80);
        return onMain(()->{
            Dialog before=noticeDialog;
            if(clear) {
                boolean sent=before!=null&&before.isShowing();
                if(sent)before.dismiss();
                if(noticeDialog==before)noticeDialog=null;
                return new JSONObject().put("sent",sent).put("verified",before==null||!before.isShowing()).put("visible",false);
            }
            PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(power==null||!power.isInteractive())throw new Failure(409,"screen_off");
            // Native notification IDs are reserved for built-in functions. Reuse
            // the same factory dialog component without replacing their cards.
            Object builder=load("com.baidu.duershow.commonui.CommonNotificationDialog$Builder").getConstructor(Context.class).newInstance(context);
            call(builder,"setTitle",new Class<?>[]{CharSequence.class},title);
            call(builder,"setMessage",new Class<?>[]{CharSequence.class},content);
            call(builder,"setIsPlayAudioEffect",new Class<?>[]{boolean.class},false);
            call(builder,"setAutoDismissTime",new Class<?>[]{long.class},8000L);
            Dialog pending=(Dialog)call(builder,"create",new Class<?>[0]);
            pending.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            pending.setOnDismissListener(dialog->{if(noticeDialog==pending)noticeDialog=null;});
            load("com.baidu.duer.common.utils.ActivityUtils").getMethod("userActivity",Context.class,int.class).invoke(null,context,0);
            context.sendBroadcast(new Intent("local.xiaodu.screencare.SHOW_VOICE").setPackage("local.xiaodu.screencare"));
            if(before!=null)before.dismiss();
            noticeDialog=pending;
            try{pending.show();}
            catch(Throwable failed){if(noticeDialog==pending)noticeDialog=null;pending.dismiss();throw failed;}
            return new JSONObject().put("sent",true).put("verified",pending.isShowing()).put("visible",pending.isShowing());
        });
    }

    private JSONObject execute(JSONObject request) throws Exception {
        keys(request,"operation","body");String op=request.getString("operation");JSONObject body=request.getJSONObject("body");
        if("camera-power".equals(op))return camera(body);
        if("notice".equals(op))return notice(body);
        throw new Failure(400,"invalid_factory_operation");
    }
    @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
        if("toString".equals(method.getName()))return METHOD;
        if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
        if("equals".equals(method.getName()))return proxy==args[0];
        if(!"call".equals(method.getName()))throw new UnsupportedOperationException();
        JSONObject result;int status=200;
        try {
            Bundle request=(Bundle)args[1];
            result=execute(new JSONObject(request==null?"{}":request.getString("request","{}")));
        }catch(Throwable error) {
            while(error instanceof InvocationTargetException||error instanceof ExecutionException)error=error.getCause();
            status=error instanceof Failure?((Failure)error).code:error instanceof JSONException?400:503;
            result=new JSONObject().put("error",error instanceof Failure?((Failure)error).reason:status==400?"invalid_arguments":"factory_device_unavailable");
        }
        Bundle reply=new Bundle();
        reply.putString("reply",Base64.encodeToString(new JSONObject().put("status",status).put("body",result).toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
        return reply;
    }
}
