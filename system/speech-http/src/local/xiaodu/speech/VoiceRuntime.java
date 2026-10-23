package local.xiaodu.speech;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.k2fsa.sherpa.onnx.*;
import local.xiaodu.timing.Milestones;
import static local.xiaodu.timing.Milestones.Point.*;

/** Local wake detection; releases the microphone to the native realtime client during calls. */
public final class VoiceRuntime {
    public interface Control {void factoryWake(boolean enabled);}
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ArrayDeque<JSONObject> events=new ArrayDeque<>();
    private static final String id=UUID.randomUUID().toString();
    private static final int KWS_PATHS=16;
    private static final float KWS_THRESHOLD=.10f,KWS_SCORE=2f;
    private static volatile Control control;
    private static android.content.Context context;
    private static volatile boolean running=false,listening=false,ending=false;
    private static volatile String state="not_configured",failure=null,partial="";
    private static long seq=0,asrStarted=0,lastChange=0,lastWake=0;
    private static volatile long samples=0,wakes=0,finals=0,rawHits=0;
    private static volatile boolean realtime=false;
    private static volatile long realtimeSeen=0;
    private static String callId=null;
    private static JSONObject clientMetrics=new JSONObject();
    private static volatile Milestones timing;
    private static final MicrophoneHandoff microphone=new MicrophoneHandoff();
    private static KeywordSpotter kws;
    private static long model=0,stream=0;

    public static synchronized void configure(Control c,android.content.Context ctx) {
        if(running)return;
        control=c;context=ctx.getApplicationContext();running=true;state="loading";
        Thread t=new Thread(VoiceRuntime::capture,"zhenweisi-local-wake");t.setDaemon(true);t.start();
        main.postDelayed(VoiceRuntime::tick,250);
    }
    private static synchronized void emit(String kind,String text) {
        try {
            JSONObject e=new JSONObject().put("seq",++seq).put("kind",kind).put("at_ms",System.currentTimeMillis());
            if(text!=null)e.put("text",text);
            events.add(e);while(events.size()>32)events.remove();
        }catch(Exception ignored){}
    }
    public static synchronized JSONObject events(long after) throws Exception {
        JSONArray list=new JSONArray();for(JSONObject e:events)if(e.getLong("seq")>after)list.put(e);
        return new JSONObject().put("instance_id",id).put("cursor",seq).put("events",list);
    }
    private static boolean testPaused() {
        if(context==null)return false;
        java.io.File marker=new java.io.File(context.getFilesDir(),"xiaodu-http/kws-pause-test");
        long age=System.currentTimeMillis()-marker.lastModified();
        return marker.isFile() && age>=0 && age<60000;
    }
    public static synchronized JSONObject status() throws Exception {
        return new JSONObject().put("state",state).put("listening",listening).put("samples",samples)
            .put("wakes",wakes).put("finals",finals).put("raw_kws_hits",rawHits)
            .put("wake_paused_for_test",testPaused()).put("kws_max_active_paths",KWS_PATHS)
            .put("kws_threshold",KWS_THRESHOLD).put("kws_score",KWS_SCORE)
            .put("wake_feedback","native_realtime").put("wake_feedback_ms",0)
            .put("realtime",realtime).put("call_id",callId==null?JSONObject.NULL:callId)
            .put("kws_recorder_reserved",microphone.kwsReserved())
            .put("client",clientMetrics).put("timing_enabled",Milestones.deviceEnabled()).put("kws_timing",timing==null?JSONObject.NULL:new JSONObject(timing.snapshot()))
            .put("error",failure==null?JSONObject.NULL:failure);
    }
    public static boolean isListening(){return listening;}
    public static void realtime(boolean enabled,String session,String phase,JSONObject metrics) {
        main.post(() -> {try{updateRealtime(enabled,session,phase,metrics);}catch(Exception failed){failure="microphone_claim_failed";}});
    }
    /** HTTP request thread waits for events; the main/capture threads never wait on it. */
    public static void claimAndAwaitRelease(String session,String phase,JSONObject metrics) throws Exception {
        CompletableFuture<Void> claimed=new CompletableFuture<>();
        long deadline=SystemClock.elapsedRealtime()+3000;
        main.post(() -> {try{updateRealtime(true,session,phase,metrics);claimed.complete(null);}catch(Exception failed){claimed.completeExceptionally(failed);}});
        claimed.get(3000,TimeUnit.MILLISECONDS);
        long remaining=deadline-SystemClock.elapsedRealtime();
        if(remaining<=0)throw new java.io.IOException("microphone_release_timeout");
        microphone.awaitReleased(session,remaining);
    }
    private static void updateRealtime(boolean enabled,String session,String phase,JSONObject metrics) throws Exception {
            if(enabled) {
                if(realtime && callId!=null && !callId.equals(session))throw new java.io.IOException("voice_session_already_active");
                if(!realtime)HttpSpeechServer.listeningStarted(() -> {});
                if(!realtime)timing=new Milestones(session,Milestones.deviceEnabled(),SystemClock::elapsedRealtimeNanos);
                microphone.claim(session);
                callId=session;realtime=true;listening=true;asrStarted=0;
                realtimeSeen=SystemClock.elapsedRealtime();state="realtime_"+phase;
                if(metrics!=null)clientMetrics=metrics;
            } else if(callId==null || callId.equals(session)) {
                finishTiming(Milestones.Outcome.ENDED);
                realtime=false;listening=false;asrStarted=0;state="wake_ready";callId=null;
                if(metrics!=null)clientMetrics=metrics;
                microphone.release(session);
            }
    }
    public static void wake() {
        main.post(() -> {
            if(control==null || testPaused() || listening || SystemClock.elapsedRealtime()-lastWake<1800)return;
            long acceptedNs=SystemClock.elapsedRealtimeNanos();
            lastWake=SystemClock.elapsedRealtime();wakes++;partial="";listening=true;ending=false;
            asrStarted=0;lastChange=lastWake;failure=null;state="realtime_connecting";emit("wake",null);
            callId=UUID.randomUUID().toString();
            timing=new Milestones(callId,Milestones.deviceEnabled(),SystemClock::elapsedRealtimeNanos);timing.markAt(KWS_ACCEPTED,acceptedNs);
            try{microphone.claim(callId);}catch(Exception busy){listening=false;state="wake_ready";failure="microphone_claim_failed";return;}
            realtime=true;realtimeSeen=lastWake;
            HttpSpeechServer.listeningStarted(() -> {
                try {
                    android.content.Intent i=new android.content.Intent();
                    i.setClassName("local.xiaodu.voice","local.xiaodu.voice.CallActivity");
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    i.putExtra("wake",true);i.putExtra("call_id",callId);
                    i.putExtra("timing_kws_accepted_ns",timing.get(KWS_ACCEPTED));context.startActivity(i);
                }catch(Exception failed){finishTiming(Milestones.Outcome.FAILED);realtime=false;listening=false;state="wake_ready";failure="voice_client_unavailable";microphone.release(callId);}
            });
        });
    }
    private static void tick() {
        if(!running)return;
        long now=SystemClock.elapsedRealtime();
        if(realtime && now-realtimeSeen>("realtime_connecting".equals(state)?45000:15000)) {
            finishTiming(Milestones.Outcome.FAILED);
            realtime=false;listening=false;state="wake_ready";microphone.release(callId);callId=null;failure="voice_client_disconnected";
        }
        main.postDelayed(VoiceRuntime::tick,250);
    }
    private static void capture() {
        AudioRecord recorder=null;
        try {
            String dir="/data/data/com.baidu.duershow.dcs/files/xiaodu-http/model/";
            KeywordSpotterConfig config=new KeywordSpotterConfig();
            config.maxActivePaths=KWS_PATHS;
            config.keywordsThreshold=KWS_THRESHOLD;config.keywordsScore=KWS_SCORE;
            config.keywordsFile=dir+"keywords.txt";
            config.modelConfig.tokens=dir+"tokens.txt";
            config.modelConfig.transducer.encoder=dir+"encoder.onnx";
            config.modelConfig.transducer.decoder=dir+"decoder.onnx";
            config.modelConfig.transducer.joiner=dir+"joiner.onnx";
            kws=new KeywordSpotter();model=kws.newFromFile(config);
            if(model==0)throw new IllegalStateException("model_rejected");
            stream=kws.createStream(model,"");
            int size=Math.max(16000,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            // The factory manifest lacks WAKE_LOCK. The existing root watcher owns
            // a named kernel suspend blocker while this runtime is healthy.
            main.post(() -> {try{control.factoryWake(false);}catch(Exception failed){failure="factory_wake_stop_failed";}});
            state="wake_ready";
            short[] pcm=new short[1600];OnlineStream input=new OnlineStream(stream);
            while(running) {
                if(microphone.clientOwns()) {
                    if(recorder!=null){
                        Milestones t=timing;if(t!=null)t.mark(KWS_STOP_BEGIN);
                        try{recorder.stop();if(t!=null)t.mark(KWS_STOP_DONE);}catch(Exception ignored){if(t!=null)t.mark(KWS_STOP_FAILED);}
                        recorder.release();recorder=null;if(t!=null)t.mark(KWS_RELEASE_DONE);microphone.kwsReleased();
                    }
                    microphone.awaitKwsPermission();continue;
                }
                if(recorder==null) {
                    if(!microphone.reserveKws())continue;
                    recorder=new AudioRecord(1,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
                    if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("mic_unavailable");
                    kws.reset(model,stream);recorder.startRecording();
                }
                int n=recorder.read(pcm,0,pcm.length);if(n<=0)throw new IllegalStateException("mic_read_failed");samples+=n;
                if(listening)continue;
                float[] data=new float[n];for(int i=0;i<n;i++)data[i]=pcm[i]/32768f;
                input.acceptWaveform(stream,data,16000);
                while(kws.isReady(model,stream)) {
                    kws.decode(model,stream);KeywordSpotterResult result=kws.getResult(model,stream);
                    if(result!=null && !result.keyword.isEmpty()){rawHits++;kws.reset(model,stream);wake();break;}
                }
            }
        }catch(Throwable failed){finishTiming(Milestones.Outcome.FAILED);failure=failed.getClass().getSimpleName()+":"+String.valueOf(failed.getMessage());state="failed";}
        finally {
            running=false;
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}
            microphone.kwsReleased();
            main.post(() -> {try{if(control!=null)control.factoryWake(true);}catch(Exception ignored){}});
            if(stream!=0)new OnlineStream(stream).delete(stream);
            if(model!=0 && kws!=null)kws.delete(model);
        }
    }
    private static void finishTiming(Milestones.Outcome outcome){
        Milestones t=timing;if(t==null||!t.finish(outcome)||context==null)return;
        try {
            java.io.File file=new java.io.File(context.getFilesDir(),"xiaodu-http/last-kws-timing.json");
            byte[] data=new JSONObject(t.snapshot()).toString().getBytes("UTF-8");
            t.store(new java.io.File(context.getFilesDir(),"xiaodu-http/timings"),data);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(data);}
            file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);
        }catch(Exception ignored){}
    }
}
