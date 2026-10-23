package local.xiaodu.speech;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.media.session.*;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Fixed device operations, served locally by the existing Magisk module.
 * No TCP listener, shell text, arbitrary intents, file paths, or microphone API.
 */
public final class DeviceControl {
    private static final String SOCKET="xiaodu-device-control-v1";
    private static final int LIMIT=3*1024*1024;
    private static Context context;
    private static AudioManager audio;
    private static final LinkedHashMap<String,String[]> APPS=new LinkedHashMap<>();
    static {
        APPS.put("netease",new String[]{"com.netease.cloudmusic.tv","网易云音乐"});
        APPS.put("bilibili",new String[]{"com.bilibili.bilithings","哔哩哔哩"});
        APPS.put("vlc",new String[]{"org.videolan.vlc","VLC"});
        APPS.put("files",new String[]{"me.zhanghai.android.files","文件"});
        APPS.put("clock",new String[]{"local.xiaodu.screencare","时钟"});
        APPS.put("settings",new String[]{"com.android.settings","设置"});
        APPS.put("easytouch",new String[]{"com.shere.easytouch","EasyTouch"});
    }
    public static final class Result {
        final int status;final byte[] data;final String type;
        Result(int status,byte[] data,String type){this.status=status;this.data=data;this.type=type;}
        JSONObject json() throws Exception{return new JSONObject(new String(data,StandardCharsets.UTF_8));}
    }
    public static Result request(String operation,JSONObject body) throws Exception {
        JSONObject request=new JSONObject().put("op",operation).put("body",body==null?new JSONObject():body);
        try(LocalSocket s=new LocalSocket()) {
            s.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));s.setSoTimeout(12000);
            DataOutputStream out=new DataOutputStream(s.getOutputStream());byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);out.write(bytes);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());int status=in.readInt(),length=in.readInt();
            if(length<0||length>LIMIT)throw new IOException("control_reply_too_large");
            byte[] data=new byte[length];in.readFully(data);
            return new Result(status,data,"screenshot".equals(operation)&&status==200?"image/png":"application/json; charset=utf-8");
        }
    }
    private static Result json(int code,JSONObject value){return new Result(code,value.toString().getBytes(StandardCharsets.UTF_8),"application/json; charset=utf-8");}
    private static final class Failure extends Exception {
        final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0)throw new SecurityException("root_bootstrap_required");
        Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        context=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        LocalServerSocket server=new LocalServerSocket(SOCKET);
        Thread listener=new Thread(() -> {
            while(true)try(LocalSocket socket=server.accept()) {
                socket.setSoTimeout(3000);
                DataInputStream in=new DataInputStream(socket.getInputStream());int length=in.readInt();
                if(length<1||length>8192)continue;
                byte[] data=new byte[length];in.readFully(data);Result result;
                try {
                    JSONObject r=new JSONObject(new String(data,StandardCharsets.UTF_8));
                    result=operate(r.getString("op"),r.getJSONObject("body"));
                }catch(Failure e){result=json(e.status,error(e.code));}
                catch(JSONException|IllegalArgumentException e){result=json(400,error("invalid_arguments"));}
                catch(Exception e){result=json(503,error("device_operation_failed").put("cause",e.getClass().getSimpleName()));}
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                out.writeInt(result.status);out.writeInt(result.data.length);out.write(result.data);out.flush();
            }catch(Exception ignored){}
        },"xiaodu-device-control");listener.start();Looper.loop();
    }
    private static JSONObject error(String code){try{return new JSONObject().put("error",code);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Result operate(String op,JSONObject body) throws Exception {
        switch(op) {
            case "status":return json(200,status());
            case "volume":return json(200,volume(body));
            case "brightness":return json(200,brightness(body));
            case "media":return json(200,media(body));
            case "screen":return json(200,screen(body));
            case "apps":return json(200,apps());
            case "app":return json(200,app(body));
            case "screenshot":return new Result(200,command("/system/bin/screencap","-p"),"image/png");
            case "voice-state":return json(200,new JSONObject().put("active",voiceRunning()));
            case "voice-end":
                boolean active=voiceRunning();
                if(active)command("/system/bin/am","startservice","-n","local.xiaodu.voice/.CallService","-a","end");
                return json(200,new JSONObject().put("requested",active));
            default:throw new Failure(404,"unknown_operation");
        }
    }
    private static void keys(JSONObject v,String... allowed) throws Failure {
        Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> it=v.keys();
        while(it.hasNext())if(!names.contains(it.next()))throw new Failure(400,"unknown_argument");
    }
    private static int integer(JSONObject v,String key,int min,int max) throws Exception {
        Object n=v.get(key);if(!(n instanceof Integer)||((Integer)n)<min||((Integer)n)>max)throw new Failure(400,"invalid_"+key);
        return (Integer)n;
    }
    private static JSONObject volume(JSONObject v) throws Exception {
        keys(v,"value","delta","mute");if(v.length()>1)throw new Failure(400,"choose_one_volume_action");
        int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Integer requested=null;
        if(v.has("value")||v.has("delta")) {
            int value=v.has("value")?integer(v,"value",0,100):Math.max(0,Math.min(100,Math.round(audio.getStreamVolume(3)*100f/max)+integer(v,"delta",-100,100)));
            requested=Math.round(value*max/100f);audio.setStreamVolume(3,requested,0);
        }
        if(v.has("mute")) {
            if(!(v.get("mute") instanceof Boolean))throw new Failure(400,"invalid_mute");
            audio.adjustStreamVolume(3,v.getBoolean("mute")?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
        }
        JSONObject result=new JSONObject().put("value",Math.round(audio.getStreamVolume(3)*100f/max)).put("index",audio.getStreamVolume(3)).put("max",max).put("muted",audio.isStreamMute(3));
        if(v.length()>0)result.put("verified",requested!=null?requested==audio.getStreamVolume(3):v.getBoolean("mute")==audio.isStreamMute(3));return result;
    }
    private static int fileInt(String path) throws Exception {
        try(BufferedReader r=new BufferedReader(new FileReader(path))){return Integer.parseInt(r.readLine().trim());}
    }
    private static JSONObject brightness(JSONObject v) throws Exception {
        keys(v,"value","delta");if(v.length()>1)throw new Failure(400,"choose_one_brightness_action");
        int raw=setting("screen_brightness");Integer requested=null;
        if(v.length()>0) {
            int percent=v.has("value")?integer(v,"value",1,100):Math.max(1,Math.min(100,Math.round(raw*100f/255)+integer(v,"delta",-100,100)));
            command("/system/bin/settings","put","system","screen_brightness_mode","0");
            requested=Math.round(percent*255f/100);command("/system/bin/settings","put","system","screen_brightness",String.valueOf(requested));
            SystemClock.sleep(250);raw=setting("screen_brightness");
        }
        JSONObject result=new JSONObject().put("value",Math.round(raw*100f/255)).put("raw",raw).put("max",255)
            .put("mode",setting("screen_brightness_mode"))
            .put("backlight",fileInt("/sys/class/leds/lcd-backlight/brightness"));
        if(requested!=null)result.put("verified",requested==raw&&(!((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive()||result.getInt("backlight")==raw));return result;
    }
    private static int setting(String name) throws Exception {
        return Integer.parseInt(new String(command("/system/bin/settings","get","system",name),StandardCharsets.UTF_8).trim());
    }
    private static List<MediaController> controllers() throws Exception {
        MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list=new ArrayList<>();
        for(MediaController c:manager.getActiveSessions(null))if(Arrays.asList("com.netease.cloudmusic.tv","com.bilibili.bilithings","org.videolan.vlc").contains(c.getPackageName()))list.add(c);
        return list;
    }
    private static String stateName(PlaybackState s){if(s==null)return "unknown";switch(s.getState()){case 3:return "playing";case 2:return "paused";case 1:return "stopped";case 6:return "buffering";case 7:return "error";default:return "state_"+s.getState();}}
    private static JSONArray actions(long flags){
        JSONArray a=new JSONArray();String[] names={"play","pause","toggle","next","previous","stop"};
        long[] bits={4,2,512,32,16,1};for(int i=0;i<bits.length;i++)if((flags&bits[i])!=0)a.put(names[i]);return a;
    }
    private static JSONObject snapshot(MediaController c) throws Exception {
        PlaybackState s=c.getPlaybackState();MediaMetadata m=c.getMetadata();
        return new JSONObject().put("package",c.getPackageName()).put("state",stateName(s)).put("position_ms",s==null?0:s.getPosition())
            .put("title",m==null?JSONObject.NULL:m.getString(MediaMetadata.METADATA_KEY_TITLE))
            .put("artist",m==null?JSONObject.NULL:m.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .put("media_id",m==null?JSONObject.NULL:m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            .put("actions",actions(s==null?0:s.getActions()));
    }
    private static JSONObject media(JSONObject v) throws Exception {
        keys(v,"action","app");List<MediaController> list=controllers();JSONArray sessions=new JSONArray();
        for(MediaController c:list)sessions.put(snapshot(c));
        if(!v.has("action"))return new JSONObject().put("sessions",sessions);
        String action=v.getString("action");long bit;
        switch(action){case "play":bit=4;break;case "pause":bit=2;break;case "toggle":bit=512;break;case "next":bit=32;break;case "previous":bit=16;break;case "stop":bit=1;break;default:throw new Failure(400,"invalid_media_action");}
        MediaController target=null;
        String selected=v.has("app")?packageFor(v.getString("app")):null;
        for(MediaController c:list)if(selected==null||selected.equals(c.getPackageName())) {
            if(target==null)target=c;if("playing".equals(stateName(c.getPlaybackState()))){target=c;break;}
        }
        if(target==null)throw new Failure(409,"no_active_media");
        JSONObject before=snapshot(target);String state=before.getString("state");
        if(("play".equals(action)&&"playing".equals(state))||("pause".equals(action)&&"paused".equals(state)))return new JSONObject().put("action",action).put("sent",false).put("verified",true).put("before",before).put("after",before);
        PlaybackState p=target.getPlaybackState();if(p==null||((p.getActions()&bit)==0&&!("toggle".equals(action)&&(p.getActions()&6)!=0)))throw new Failure(409,"media_action_unsupported");
        MediaController.TransportControls t=target.getTransportControls();
        if("toggle".equals(action)){if("playing".equals(state))t.pause();else t.play();}
        else if("play".equals(action))t.play();else if("pause".equals(action))t.pause();else if("next".equals(action))t.skipToNext();else if("previous".equals(action))t.skipToPrevious();else t.stop();
        JSONObject after=before;boolean verified=false;long end=SystemClock.elapsedRealtime()+2000;
        do {
            SystemClock.sleep(150);after=snapshot(target);String now=after.getString("state");
            if("next".equals(action)||"previous".equals(action))verified=!before.optString("media_id").equals(after.optString("media_id"))||!before.optString("title").equals(after.optString("title"));
            else {String expected="toggle".equals(action)?("playing".equals(state)?"paused":"playing"):("play".equals(action)?"playing":"pause".equals(action)?"paused":"stopped");verified=expected.equals(now);}
        }while(!verified&&SystemClock.elapsedRealtime()<end);
        return new JSONObject().put("action",action).put("sent",true).put("verified",verified).put("before",before).put("after",after);
    }
    private static JSONObject currentApp() throws Exception {
        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks=am.getRunningTasks(1);
        ComponentName c=tasks.isEmpty()?null:tasks.get(0).topActivity;
        return new JSONObject().put("package",c==null?JSONObject.NULL:c.getPackageName()).put("activity",c==null?JSONObject.NULL:c.getClassName());
    }
    private static boolean voiceRunning() {
        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningServiceInfo s:am.getRunningServices(256))if("local.xiaodu.voice".equals(s.service.getPackageName())&&s.service.getClassName().endsWith(".CallService"))return true;
        return false;
    }
    private static String packageFor(String id) throws Failure {
        String[] app=APPS.get(id);if(app==null)throw new Failure(400,"unknown_app");return app[0];
    }
    private static JSONObject apps() throws Exception {
        JSONArray list=new JSONArray();
        for(Map.Entry<String,String[]> entry:APPS.entrySet())try {
            context.getPackageManager().getApplicationInfo(entry.getValue()[0],0);
            list.put(new JSONObject().put("id",entry.getKey()).put("package",entry.getValue()[0]).put("label",entry.getValue()[1]));
        }catch(PackageManager.NameNotFoundException absent){}
        return new JSONObject().put("apps",list).put("current",currentApp());
    }
    private static void dismissClock() throws Exception {
        command("/system/bin/am","broadcast","-a","local.xiaodu.screencare.SHOW_VOICE","-p","local.xiaodu.screencare");
    }
    private static JSONObject app(JSONObject v) throws Exception {
        keys(v,"action","app");if(!v.has("action"))return currentApp();
        String action=v.getString("action"),expected=null;
        if("open".equals(action)) {
            String pkg=packageFor(v.getString("app"));Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch==null)throw new Failure(404,"app_unavailable");
            command("/system/bin/input","keyevent","224");dismissClock();
            command("/system/bin/am","start","-W","-n",launch.getComponent().flattenToString());expected=pkg;
        }else if("home".equals(action)) {
            command("/system/bin/input","keyevent","224");dismissClock();
            command("/system/bin/am","start","-W","-a","android.intent.action.MAIN","-c","android.intent.category.HOME");expected="me.efesser.flauncher";
        }else if("back".equals(action))command("/system/bin/input","keyevent","4");
        else throw new Failure(400,"invalid_app_action");
        SystemClock.sleep(150);JSONObject current=currentApp();
        return new JSONObject().put("action",action).put("sent",true).put("verified",expected==null?JSONObject.NULL:expected.equals(current.optString("package"))).put("current",current);
    }
    private static JSONObject screen(JSONObject v) throws Exception {
        keys(v,"action");String action=v.optString("action","");
        if("on".equals(action)||"off".equals(action))command("/system/bin/input","keyevent","on".equals(action)?"224":"223");
        else if("home".equals(action))return app(new JSONObject().put("action","home"));
        else if("clock".equals(action))return app(new JSONObject().put("action","open").put("app","clock"));
        else if(!action.isEmpty())throw new Failure(400,"invalid_screen_action");
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        int backlight=fileInt("/sys/class/leds/lcd-backlight/brightness");boolean verified=false;
        if(!action.isEmpty()) {
            long until=SystemClock.elapsedRealtime()+2000;
            do {
                SystemClock.sleep(100);backlight=fileInt("/sys/class/leds/lcd-backlight/brightness");
                verified="on".equals(action)?power.isInteractive()&&backlight>0:!power.isInteractive()&&backlight==0;
            }while(!verified&&SystemClock.elapsedRealtime()<until);
        }
        JSONObject result=new JSONObject().put("interactive",power.isInteractive()).put("backlight",backlight);
        if(!action.isEmpty())result.put("action",action).put("verified",verified);return result;
    }
    private static JSONObject status() throws Exception {
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        StatFs disk=new StatFs(Environment.getDataDirectory().getAbsolutePath());
        String address="";java.net.NetworkInterface wifi=java.net.NetworkInterface.getByName("wlan0");
        if(wifi!=null){Enumeration<java.net.InetAddress> addresses=wifi.getInetAddresses();while(addresses.hasMoreElements()){java.net.InetAddress ip=addresses.nextElement();if(ip instanceof java.net.Inet4Address){address=ip.getHostAddress();break;}}}
        return new JSONObject().put("model",Build.MODEL).put("volume",volume(new JSONObject())).put("brightness",brightness(new JSONObject()))
            .put("screen",screen(new JSONObject())).put("current_app",currentApp()).put("media",media(new JSONObject()))
            .put("network",new JSONObject().put("ipv4",address).put("connected",!address.isEmpty()))
            .put("memory",new JSONObject().put("available_bytes",memory.availMem).put("total_bytes",memory.totalMem))
            .put("storage",new JSONObject().put("available_bytes",disk.getAvailableBytes()).put("total_bytes",disk.getTotalBytes()));
    }
    private static byte[] command(String... args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectError(new File("/dev/null")).start();
        FutureTask<byte[]> read=new FutureTask<>(() -> {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;
            try(InputStream in=process.getInputStream()){while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>LIMIT)throw new IOException("output_too_large");}}return out.toByteArray();
        });new Thread(read,"device-command-output").start();
        try {
            if(!process.waitFor(6,TimeUnit.SECONDS))throw new Failure(504,"device_command_timeout");
            byte[] bytes=read.get(1,TimeUnit.SECONDS);if(process.exitValue()!=0)throw new Failure(503,"device_command_failed");return bytes;
        }finally{process.destroy();}
    }
}
