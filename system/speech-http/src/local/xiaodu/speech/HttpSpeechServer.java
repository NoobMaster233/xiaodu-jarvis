package local.xiaodu.speech;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Small HTTP service loaded into the existing, authenticated factory voice host.
 * All speech operations run on its main looper. No shell/ADB/Frida requests are
 * exposed by HTTP; the bootstrap tool can detach after this Java service starts.
 */
public final class HttpSpeechServer {
    private static HttpSpeechServer instance;
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList(
        "completed", "stopped", "failed", "expired", "unconfirmed"));
    private static final Set<Integer> VOICES = new HashSet<>(Arrays.asList(
        0, 1, 3, 4, 5, 103, 106, 110, 111, 5003, 5118));
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final int port;
    private final AudioManager audio;
    private final ServerSocket server;
    private final ThreadPoolExecutor requests;
    private Job active;
    private final Runnable pumpTask = this::pump;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == -1 || change == -2) main.post(() -> stopActive("audio_focus_lost"));
    };

    private static final class Job {
        String id, text, digest, state="queued", reason;
        int voice, characters;
        long created=System.currentTimeMillis(), queuedAt=SystemClock.elapsedRealtime(), started;
        long updated=created;
        Object listener;
        boolean overridden, cancelRequested;
        Boolean voiceRestored;
        JSONObject json() throws Exception {
            JSONObject value = new JSONObject().put("id",id).put("state",state)
                .put("characters",characters).put("voice",voice)
                .put("created_ms",created).put("updated_ms",updated);
            if(reason!=null)value.put("reason",reason);
            if(voiceRestored!=null)value.put("voice_restored",voiceRestored);
            if(cancelRequested)value.put("cancel_requested",true);
            return value;
        }
    }

    public static synchronized String start(Context context) throws Exception {
        if(instance!=null)return "already_running";
        instance=new HttpSpeechServer(context.getApplicationContext());
        instance.accept();
        return "started";
    }

    public static void listeningStarted(Runnable quiet) {
        if(instance==null){quiet.run();return;}
        instance.main.post(() -> {
            synchronized(instance.lock) {
                for(Job j:instance.queue){j.state="stopped";j.reason="user_listening";j.text=null;}
                instance.queue.clear();instance.stopActive("user_listening");
            }
            quiet.run();
        });
    }

    private HttpSpeechServer(Context context) throws Exception {
        this.context=context;
        port=8765;
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        requests=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r -> {
            Thread t=new Thread(r,"xiaodu-http-request");t.setDaemon(true);return t;
        });
        server=new ServerSocket();server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"),port),8);
    }

    private void accept() {
        Thread t=new Thread(() -> {
            while(!server.isClosed())try {
                Socket socket=server.accept();socket.setSoTimeout(3000);
                try { requests.execute(() -> handle(socket)); }
                catch(RejectedExecutionException busy) { socket.close(); }
            }catch(IOException ignored) { if(server.isClosed())return; }
        },"xiaodu-http-listener");
        t.setDaemon(true);t.start();
    }

    private void handle(Socket socket) {
        try(Socket close=socket;InputStream in=new BufferedInputStream(socket.getInputStream())) {
            String first=line(in,2048);
            String[] request=first.split(" ");
            if(request.length!=3 || !request[1].startsWith("/") || !request[2].startsWith("HTTP/1.")) {
                reply(socket,400,error("invalid_request"));return;
            }
            Map<String,String> headers=new HashMap<>();int total=first.length();
            while(true) {
                String h=line(in,4096);total+=h.length()+2;
                if(total>8192){reply(socket,431,error("headers_too_large"));return;}
                if(h.isEmpty())break;
                int colon=h.indexOf(':');if(colon<1){reply(socket,400,error("invalid_header"));return;}
                String key=h.substring(0,colon).trim().toLowerCase(Locale.US);
                if(headers.put(key,h.substring(colon+1).trim())!=null){reply(socket,400,error("duplicate_header"));return;}
            }
            String method=request[0],path=request[1];
            if("GET".equals(method) && "/health".equals(path)) {
                reply(socket,200,new JSONObject().put("service","xiaodu-speech").put("version","2.3")
                    .put("instance_id",instanceId).put("engine_ready",engineReady()));return;
            }
            if("GET".equals(method) && path.startsWith("/v1/voice/events")) {
                long after=0;int query=path.indexOf("?after=");
                if(query>=0)after=Long.parseLong(path.substring(query+7));
                reply(socket,200,VoiceRuntime.events(after));return;
            }
            if("GET".equals(method) && "/v1/voice/status".equals(path)) {
                reply(socket,200,VoiceRuntime.status());return;
            }
            if("POST".equals(method) && "/v1/voice/start".equals(path)) {
                VoiceRuntime.wake();reply(socket,202,new JSONObject().put("accepted",true));return;
            }
            Map<String,String> controls=new HashMap<>();
            controls.put("/v1/device","status");controls.put("/v1/volume","volume");
            controls.put("/v1/brightness","brightness");controls.put("/v1/media","media");
            controls.put("/v1/screen","screen");controls.put("/v1/apps","apps");
            controls.put("/v1/app","app");controls.put("/v1/screenshot","screenshot");
            if("GET".equals(method) && controls.containsKey(path)) {
                control(socket,controls.get(path),new JSONObject());return;
            }
            if(headers.containsKey("transfer-encoding")){reply(socket,400,error("content_length_required"));return;}
            int length;
            try { length=Integer.parseInt(headers.getOrDefault("content-length","0")); }
            catch(NumberFormatException bad){reply(socket,400,error("invalid_content_length"));return;}
            if(length<0 || length>8192){reply(socket,413,error("body_too_large"));return;}
            byte[] body=new byte[length];int offset=0;
            while(offset<length) {int n=in.read(body,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
            if("POST".equals(method) && controls.containsKey(path) && !Arrays.asList("/v1/device","/v1/apps","/v1/screenshot").contains(path)) {
                JSONObject value;
                try{value=new JSONObject(new String(body,StandardCharsets.UTF_8));}
                catch(Exception bad){reply(socket,400,error("invalid_json"));return;}
                control(socket,controls.get(path),value);return;
            }
            if("POST".equals(method) && "/v1/voice/end".equals(path)) {
                try {
                    DeviceControl.Result result;
                    result=DeviceControl.request("voice-end",new JSONObject());
                    if(result.status!=200){reply(socket,result.status,result.json());return;}
                    long until=SystemClock.elapsedRealtime()+5000;
                    boolean active;
                    do {
                        active=DeviceControl.request("voice-state",new JSONObject()).json().optBoolean("active",true);
                        if(!active&&!VoiceRuntime.status().optBoolean("realtime"))break;
                        SystemClock.sleep(100);
                    }while(SystemClock.elapsedRealtime()<until);
                    JSONObject value=VoiceRuntime.status();value.put("verified",!active&&!value.optBoolean("realtime"));reply(socket,200,value);
                }catch(Exception failed){reply(socket,503,error("device_control_unavailable"));}
                return;
            }
            if("POST".equals(method) && "/v1/voice/realtime".equals(path)) {
                JSONObject value=new JSONObject(new String(body,StandardCharsets.UTF_8));
                String session=value.getString("id");java.util.UUID.fromString(session);
                if(value.getBoolean("active")&&value.optBoolean("await_release",false)) {
                    try {
                        VoiceRuntime.claimAndAwaitRelease(session,value.optString("phase","connecting"),value.optJSONObject("metrics"));
                        reply(socket,200,new JSONObject().put("accepted",true).put("id",session).put("microphone_released",true));
                    }catch(Exception failed){reply(socket,503,error("microphone_release_unconfirmed"));}
                } else {
                    VoiceRuntime.realtime(value.getBoolean("active"),session,value.optString("phase","connected"),value.optJSONObject("metrics"));
                    reply(socket,200,new JSONObject().put("accepted",true));
                }
                return;
            }
            if("POST".equals(method) && "/v1/speech".equals(path)) {
                JSONObject value;
                try {value=new JSONObject(new String(body,StandardCharsets.UTF_8));}
                catch(Exception bad){reply(socket,400,error("invalid_json"));return;}
                submit(socket,value);return;
            }
            if("GET".equals(method) && "/v1/status".equals(path)) {
                synchronized(lock) {
                    JSONObject value=new JSONObject().put("instance_id",instanceId).put("default_voice",4)
                        .put("queued",queue.size()).put("active",active==null?JSONObject.NULL:active.json());
                    Job last=null;for(Job j:jobs.values())last=j;
                    value.put("last",last==null?JSONObject.NULL:last.json());reply(socket,200,value);
                }return;
            }
            if(path.startsWith("/v1/speech/")) {
                String id=path.substring("/v1/speech/".length());
                if(!id.matches("[A-Za-z0-9_-]{1,100}")){reply(socket,400,error("invalid_id"));return;}
                if("GET".equals(method)) {
                    synchronized(lock) {Job j=jobs.get(id);reply(socket,j==null?404:200,j==null?error("unknown_request"):j.json());}return;
                }
                if("DELETE".equals(method)){cancel(socket,id);return;}
            }
            if("POST".equals(method) && "/v1/stop".equals(path)){cancel(socket,null);return;}
            reply(socket,404,error("not_found"));
        }catch(Exception ignored) { /* Never log request text, credentials, or raw errors. */ }
    }

    private void control(Socket socket,String operation,JSONObject body) throws Exception {
        try {
            DeviceControl.Result result=DeviceControl.request(operation,body);
            if("status".equals(operation) && result.status==200) {
                JSONObject value=result.json().put("voice",VoiceRuntime.status());
                synchronized(lock){value.put("queued",queue.size()).put("active",active==null?JSONObject.NULL:active.json());}
                reply(socket,200,value);
            }else replyBytes(socket,result.status,result.type,result.data);
        }catch(Exception failed){reply(socket,503,error("device_control_unavailable"));}
    }

    private void submit(Socket socket,JSONObject value) throws Exception {
        Object rawText=value.opt("text"), rawId=value.opt("id"), rawVoice=value.opt("voice");
        if(!(rawText instanceof String) || (rawId!=null && !(rawId instanceof String))) {reply(socket,400,error("invalid_text_or_id"));return;}
        String text=((String)rawText).trim(), id=rawId==null?UUID.randomUUID().toString():(String)rawId;
        if(text.isEmpty() || text.length()>1000 || text.indexOf('\0')>=0 || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            reply(socket,400,error("invalid_text_or_id"));return;
        }
        int voice=4;
        if(rawVoice!=null && rawVoice!=JSONObject.NULL) {
            if(!(rawVoice instanceof Integer) || !VOICES.contains((Integer)rawVoice)){reply(socket,400,error("invalid_voice"));return;}
            voice=(Integer)rawVoice;
        }
        String digest=hex(MessageDigest.getInstance("SHA-256").digest((voice+"\n"+text).getBytes(StandardCharsets.UTF_8)));
        synchronized(lock) {
            Job previous=jobs.get(id);
            if(previous!=null) {
                if(!previous.digest.equals(digest)){reply(socket,409,error("request_id_conflict"));return;}
                reply(socket,200,previous.json().put("deduplicated",true));return;
            }
            if(!engineReady()){reply(socket,503,error("engine_not_ready"));return;}
            if(queue.size()>=8){reply(socket,429,error("queue_full"));return;}
            while(jobs.size()>=64) {
                Iterator<Job> old=jobs.values().iterator();boolean removed=false;
                while(old.hasNext())if(TERMINAL.contains(old.next().state)){old.remove();removed=true;break;}
                if(!removed){reply(socket,429,error("queue_full"));return;}
            }
            Job j=new Job();j.id=id;j.text=text;j.digest=digest;j.voice=voice;j.characters=text.length();
            jobs.put(id,j);queue.add(j);main.post(pumpTask);
            reply(socket,202,j.json().put("instance_id",instanceId));
        }
    }

    private void cancel(Socket socket,String id) throws Exception {
        synchronized(lock) {
            if(id!=null && !jobs.containsKey(id)){reply(socket,404,error("unknown_request"));return;}
            int cancelled=0;
            Iterator<Job> items=queue.iterator();
            while(items.hasNext()) {Job j=items.next();if(id==null || id.equals(j.id)) {
                items.remove();j.state="stopped";j.reason="cancelled_before_playback";j.text=null;j.updated=System.currentTimeMillis();cancelled++;
            }}
            String current=null;
            if(active!=null && (id==null || id.equals(active.id))) {
                active.cancelRequested=true;current=active.id;
                final String target=current;main.post(() -> {synchronized(lock){if(active!=null && active.id.equals(target))stopActive("requested");}});
            }
            reply(socket,200,new JSONObject().put("queued_cancelled",cancelled)
                .put("active_cancel_requested",current==null?JSONObject.NULL:current));
        }
    }

    private Object engine() throws Exception {
        Class<?> cls=Class.forName("com.baidu.duer.dcs.tts.TtsImpl",true,context.getClassLoader());
        return cls.getMethod("getInstance").invoke(null);
    }
    private boolean engineReady() {
        try { return field(engine(),"mSpeechSynthesizer")!=null; } catch(Exception notReady){return false;}
    }
    private static Object field(Object o,String name) throws Exception {
        Field f=o.getClass().getField(name);f.setAccessible(true);return f.get(o);
    }
    private static Object invoke(Object o,String name,Class<?>[] types,Object...args) throws Exception {
        Method m=o.getClass().getMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    private boolean others(Object tts,Job ours) throws Exception {
        Map<?,?> listeners=(Map<?,?>)field(tts,"mListernerMap");
        for(Object value:listeners.values()) {
            Object listener=invoke(value,"getListener",new Class<?>[0]);
            if(ours==null || listener!=ours.listener)return true;
        }return false;
    }
    private void setVoice(Object tts,int voice) throws Exception {
        Object synth=field(tts,"mSpeechSynthesizer");
        String key=(String)synth.getClass().getField("PARAM_SPEAKER").get(null);
        int result=(Integer)invoke(synth,"setParam",new Class<?>[]{String.class,String.class},key,String.valueOf(voice));
        if(result!=0)throw new IllegalStateException("Voice rejected");
    }
    private void finish(Job job,String state,String reason) {
        synchronized(lock) {
            if(active!=job)return;
            if(job.overridden)try {
                Object tts=engine(),params=invoke(tts,"getTtsParam",new Class<?>[0]);
                int voice=(Integer)invoke(params,"getSpeaker",new Class<?>[0]);
                setVoice(tts,voice);job.voiceRestored=true;
            }catch(Exception failed){job.voiceRestored=false;state="unconfirmed";reason="voice_restore_failed";}
            job.state=state;job.reason=reason;job.updated=System.currentTimeMillis();job.text=null;job.listener=null;
            active=null;audio.abandonAudioFocus(focus);main.post(pumpTask);
        }
    }
    private void stopActive(String reason) {
        synchronized(lock) {
            if(active==null)return;Job job=active;
            try {
                Object tts=engine();
                if(others(tts,job)) {
                    Map<?,?> listeners=(Map<?,?>)field(tts,"mListernerMap");
                    Iterator<?> it=listeners.entrySet().iterator();
                    while(it.hasNext()) {
                        Map.Entry<?,?> entry=(Map.Entry<?,?>)it.next();
                        if(invoke(entry.getValue(),"getListener",new Class<?>[0])==job.listener)it.remove();
                    }
                    finish(job,"unconfirmed","other_tts_present_no_global_stop");return;
                }
                invoke(tts,"stop",new Class<?>[0]);
                finish(job,"timeout".equals(reason)?"failed":"stopped",reason);
            }catch(Exception failed){finish(job,"unconfirmed","stop_failed");}
        }
    }
    private void pump() {
        synchronized(lock) {
            main.removeCallbacks(pumpTask);
            if(active!=null) {
                if(SystemClock.elapsedRealtime()-active.started>120000)stopActive("timeout");
                else main.postDelayed(pumpTask,500);
                return;
            }
            while(!queue.isEmpty() && SystemClock.elapsedRealtime()-queue.peek().queuedAt>120000) {
                Job j=queue.remove();j.state="expired";j.reason="queue_timeout";j.text=null;j.updated=System.currentTimeMillis();
            }
            if(queue.isEmpty())return;
            Job job=queue.peek();
            try {
                Object tts=engine();
                Class<?> globals=Class.forName("com.baidu.duer.dcs.util.util.DcsGlobalConfig",true,context.getClassLoader());
                if(VoiceRuntime.isListening() || !engineReady() || others(tts,null) || globals.getField("isAsring").getBoolean(null)) {
                    main.postDelayed(pumpTask,500);return;
                }
                queue.remove();active=job;job.started=SystemClock.elapsedRealtime();job.state="starting";job.updated=System.currentTimeMillis();
                Class<?> listenerClass=Class.forName("com.baidu.duer.dcs.tts.ITts$TtsListener",true,context.getClassLoader());
                job.listener=java.lang.reflect.Proxy.newProxyInstance(context.getClassLoader(),new Class<?>[]{listenerClass},(proxy,method,args) -> {
                    String name=method.getName();
                    if("equals".equals(name))return proxy==args[0];
                    if("hashCode".equals(name))return System.identityHashCode(proxy);
                    if("toString".equals(name))return "SpeechHttpListener";
                    main.post(() -> {synchronized(lock){if(active!=job)return;
                        if("onTtsStart".equals(name)){job.state="playing";job.updated=System.currentTimeMillis();}
                        else if("onTtsFinish".equals(name))finish(job,"completed",null);
                        else if("onTtsError".equals(name))finish(job,"failed","factory_tts_error");
                    }});return null;
                });
                if(audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    finish(job,"failed","audio_focus_denied");return;
                }
                job.overridden=true;setVoice(tts,job.voice);
                invoke(tts,"speak",new Class<?>[]{String.class,listenerClass},job.text,job.listener);
                main.postDelayed(pumpTask,500);
            }catch(Exception failed) {
                if(active==job)finish(job,"failed","engine_operation_failed");
                else main.postDelayed(pumpTask,1000);
            }
        }
    }

    private static JSONObject error(String code) throws Exception {return new JSONObject().put("error",code);}
    private static void reply(Socket socket,int status,JSONObject value) throws IOException {
        replyBytes(socket,status,"application/json; charset=utf-8",value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void replyBytes(Socket socket,int status,String type,byte[] body) throws IOException {
        String reason=status<300?"OK":"Error";
        String headers="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        OutputStream out=socket.getOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    private static String line(InputStream in,int maximum) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=-1;
        for(int i=0;i<maximum;i++) {
            int b=in.read();if(b<0)throw new EOFException();
            if(previous==13 && b==10) {
                byte[] bytes=out.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);
            }
            out.write(b);previous=b;
        }throw new IOException("Header too long");
    }
    private static byte[] readBounded(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[512];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Too large");out.write(buffer,0,n);}return out.toByteArray();
    }
    private static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format(Locale.US,"%02x",value&255));return b.toString();
    }
}
