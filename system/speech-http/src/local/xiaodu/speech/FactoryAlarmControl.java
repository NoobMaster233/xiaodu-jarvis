package local.xiaodu.speech;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Thin adapter to the existing factory alarm engine and its native IPC provider.
 * No alarm scheduler, ringtone player, database writes or new listener here.
 */
public final class FactoryAlarmControl implements InvocationHandler {
    public static final String METHOD="xiaodu_alarm_control_v1";
    private static FactoryAlarmControl instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ClassLoader loader;
    private final Class<?> handlerClass,payloadClass,deleteClass,schedulerClass;
    private static final class Failure extends Exception {
        final int code;final String reason;
        Failure(int code,String reason){this.code=code;this.reason=reason;}
    }
    private FactoryAlarmControl(Context context) throws Exception {
        loader=context.getClassLoader();
        handlerClass=load("com.baidu.duer.alarm.model.AlertHandler");
        payloadClass=load("com.baidu.duershow.protocol.dcs.alerts.directive.SetAlertPayload");
        deleteClass=load("com.baidu.duershow.protocol.dcs.alerts.directive.DeleteAlertPayload");
        schedulerClass=load("com.baidu.duer.alarm.model.AlertScheduler");
        handlerClass.getMethod("handleSetAlertPayload",payloadClass);
        handlerClass.getMethod("handleDeleteAlertPayload",deleteClass);
        handlerClass.getDeclaredField("e");schedulerClass.getDeclaredField("b");
    }
    public static synchronized String start(Context context) throws Exception {
        if(!"com.baidu.launcher".equals(context.getPackageName())||android.os.Build.VERSION.SDK_INT!=27)
            throw new SecurityException("factory_alarm_host_required");
        if(context.getPackageManager().getPackageInfo("com.baidu.launcher",0).versionCode!=10682)
            throw new SecurityException("factory_alarm_version_unsupported");
        if(instance!=null)return "already_running";
        FactoryAlarmControl candidate=new FactoryAlarmControl(context);
        Class<?> callback=candidate.load("com.baidu.duer.net.IRemoteRequest");
        Object proxy=Proxy.newProxyInstance(candidate.loader,new Class<?>[]{callback},candidate);
        HashMap<String,Object> methods=new HashMap<>();methods.put(METHOD,proxy);
        candidate.load("com.baidu.duer.net.RemoteReceiverManager").getMethod("register",Map.class).invoke(null,methods);
        instance=candidate;return "started";
    }
    private Class<?> load(String name) throws Exception {return Class.forName(name,true,loader);}
    private static Object field(Object value,String name) throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=object.getClass().getMethod(name,types);method.setAccessible(true);return method.invoke(object,args);
    }
    private Object engine() throws Exception {return handlerClass.getMethod("getInstance").invoke(null);}
    @SuppressWarnings("unchecked")
    private Map<String,Object> alarms() throws Exception {return (Map<String,Object>)call(engine(),"getDuerAlertMap",new Class<?>[0]);}
    private ScheduledFuture<?> future(String id) throws Exception {
        Object scheduler=schedulerClass.getMethod("getInstance").invoke(null);
        return (ScheduledFuture<?>)((Map<?,?>)field(scheduler,"b")).get(id);
    }
    private static long date(Object alarm) throws Exception {return (Long)field(alarm,"realScheduledDate");}
    private static String format(long when) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date(when));
    }
    private JSONObject describe(Object alarm) throws Exception {
        String id=(String)field(alarm,"token");ScheduledFuture<?> f=future(id);
        JSONObject result=new JSONObject().put("id",id).put("at",format(date(alarm)))
            .put("at_ms",date(alarm)).put("scheduled",f!=null&&!f.isCancelled()&&!f.isDone());
        if("TIMER".equals(field(alarm,"type")))result.put("remaining_seconds",Math.max(0,(date(alarm)-System.currentTimeMillis()+999)/1000));
        return result;
    }
    private <T> T onMain(Callable<T> work) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return work.call();
        FutureTask<T> task=new FutureTask<>(work);
        if(!main.post(task))throw new Failure(503,"alarm_host_unavailable");
        try{return task.get(2500,TimeUnit.MILLISECONDS);}
        finally{if(!task.isDone())task.cancel(false);}
    }
    private boolean persisted(String id,Long expected) throws Exception {
        Object dao=onMain(()->field(engine(),"e"));
        List<?> rows=(List<?>)call(dao,"getAllAlert",new Class<?>[0]);
        for(Object row:rows)if(id.equals(field(row,"token")))return expected==null||date(row)==expected;
        return false;
    }
    private boolean awaitDatabase(String id,Long expected,boolean present) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+2000;
        do {
            if(persisted(id,expected)==present)return true;
            if(SystemClock.elapsedRealtime()>=deadline)return false;
            Thread.sleep(40);
        }while(true);
    }
    private static void keys(JSONObject body,String... allowed) throws Exception {
        Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> it=body.keys();
        while(it.hasNext())if(!names.contains(it.next()))throw new Failure(400,"unknown_argument");
    }
    private static String id(JSONObject body) throws Exception {
        Object value=body.get("id");
        if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>200||((String)value).matches("(?s).*[\\x00-\\x1f\\x7f].*"))throw new Failure(400,"invalid_alarm_id");
        return (String)value;
    }
    private static long integer(JSONObject body,String key) throws Exception {
        Object value=body.get(key);
        if(!(value instanceof Integer)&&!(value instanceof Long))throw new Failure(400,"invalid_alarm_time");
        return ((Number)value).longValue();
    }
    private JSONObject execute(JSONObject body) throws Exception {
        String action=body.optString("action","list");
        if("ping".equals(action)){keys(body,"action");return new JSONObject().put("service","xiaodu-factory-alarms").put("version",1);}
        String kind=body.optString("kind","ALARM");
        if(!"ALARM".equals(kind)&&!"TIMER".equals(kind))throw new Failure(400,"invalid_alert_kind");
        if("stop".equals(action)) {
            keys(body,"action");
            JSONObject result=onMain(()->{
                Object ring=load("com.baidu.duer.alarm.model.RingManager").getMethod("getInstance").invoke(null);
                boolean ringing=(Boolean)call(ring,"isRinging",new Class<?>[0]);
                Object alert=call(ring,"getRingingAlert",new Class<?>[0]);
                JSONObject value=new JSONObject().put("sent",ringing).put("id",alert==null?JSONObject.NULL:field(alert,"token"));
                if(ringing) {
                    Object proxy=load("com.baidu.duer.alarm.proxy.AlarmProxy").getConstructor().newInstance();
                    call(proxy,"closeAlarm",new Class<?>[0]);
                }
                return value;
            });
            long deadline=SystemClock.elapsedRealtime()+2000;
            boolean ringing;
            do {
                ringing=onMain(()->{
                    Object ring=load("com.baidu.duer.alarm.model.RingManager").getMethod("getInstance").invoke(null);
                    return (Boolean)call(ring,"isRinging",new Class<?>[0]);
                });
                if(!ringing||SystemClock.elapsedRealtime()>=deadline)break;
                Thread.sleep(40);
            }while(true);
            return result.put("ringing",ringing).put("verified",!ringing);
        }
        if("list".equals(action)) {
            keys(body,"action","kind");
            return onMain(()->{
                List<Object> all=new ArrayList<>(alarms().values());all.sort(Comparator.comparingLong(a->{try{return date(a);}catch(Exception e){return Long.MAX_VALUE;}}));
                JSONArray values=new JSONArray();
                for(Object alarm:all)if(kind.equals(field(alarm,"type")))values.put(describe(alarm));
                return new JSONObject().put("TIMER".equals(kind)?"timers":"alarms",values);
            });
        }
        if("add".equals(action)) {
            keys(body,"action","kind","id","at_ms","after_minutes","duration_seconds");String id=id(body);
            if(!id.matches("xiaodu-[A-Za-z0-9_-]{1,80}"))throw new Failure(400,"invalid_alarm_id");
            final long seconds;
            long when;
            if("TIMER".equals(kind)) {
                if(!body.has("duration_seconds")||body.has("at_ms")||body.has("after_minutes"))throw new Failure(400,"invalid_timer_time");
                seconds=integer(body,"duration_seconds");
                if(seconds<1||seconds>86400)throw new Failure(400,"invalid_timer_time");
                when=System.currentTimeMillis()+seconds*1000;
            }else {
              seconds=0;
              if(body.has("duration_seconds")||body.has("at_ms")==body.has("after_minutes"))throw new Failure(400,"choose_one_alarm_time");
              if(body.has("after_minutes")) {
                long minutes=integer(body,"after_minutes");
                if(minutes<1||minutes>525600)throw new Failure(400,"invalid_alarm_time");
                when=System.currentTimeMillis()+minutes*60000;
              }else when=integer(body,"at_ms");
            }
            when=(when/1000)*1000;
            if(when<=System.currentTimeMillis()||when>253402300799000L)throw new Failure(400,"invalid_alarm_time");
            final long at=when;
            JSONObject result=onMain(()->{
                Object existing=alarms().get(id);
                if(existing!=null) {
                    if(!kind.equals(field(existing,"type"))||("TIMER".equals(kind)?((Number)field(existing,"duration")).longValue()!=seconds:date(existing)!=at))throw new Failure(409,"alarm_id_conflict");
                    return describe(existing).put("sent",false);
                }
                Object payload=payloadClass.getConstructor().newInstance();
                payloadClass.getField("token").set(payload,id);payloadClass.getField("type").set(payload,kind);
                if("TIMER".equals(kind))payloadClass.getField("duration").setLong(payload,seconds);
                payloadClass.getField("category").set(payload,"NORMAL");
                payloadClass.getField("assets").set(payload,new ArrayList<>());
                payloadClass.getField("assetPlayOrder").set(payload,new ArrayList<>());
                payloadClass.getField("scheduledTime").set(payload,new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).format(new Date(at)));
                handlerClass.getMethod("handleSetAlertPayload",payloadClass).invoke(engine(),payload);
                Object added=alarms().get(id);
                if(added==null)throw new Failure(503,"alarm_not_scheduled");
                return describe(added).put("sent",true);
            });
            boolean verified=result.optBoolean("scheduled")&&awaitDatabase(id,result.getLong("at_ms"),true);
            return result.put("verified",verified);
        }
        if("delete".equals(action)) {
            keys(body,"action","kind","id");String id=id(body);
            boolean sent=onMain(()->{
                if(!alarms().containsKey(id))return false;
                if(!kind.equals(field(alarms().get(id),"type")))throw new Failure(409,"alarm_type_mismatch");
                Object payload=deleteClass.getConstructor().newInstance();deleteClass.getField("token").set(payload,id);
                handlerClass.getMethod("handleDeleteAlertPayload",deleteClass).invoke(engine(),payload);return true;
            });
            boolean absent=onMain(()->!alarms().containsKey(id)&&future(id)==null);
            return new JSONObject().put("id",id).put("sent",sent).put("verified",absent&&awaitDatabase(id,null,false));
        }
        throw new Failure(400,"invalid_alarm_action");
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
            result=new JSONObject().put("error",error instanceof Failure?((Failure)error).reason:status==400?"invalid_arguments":"factory_alarm_unavailable");
        }
        JSONObject envelope=new JSONObject().put("status",status).put("body",result);
        Bundle reply=new Bundle();reply.putString("reply",Base64.encodeToString(envelope.toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
        return reply;
    }
}
