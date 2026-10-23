package local.xiaodu.voice;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import local.xiaodu.timing.Milestones;
import static local.xiaodu.timing.Milestones.Point.*;

/** Media lives in libwebrtc; signaling goes to the owner's existing Codex bridge. */
public final class CallService extends Service {
    static volatile boolean active=false,muted=false;
    static volatile String label="准备就绪";
    static volatile float inputLevel=0,outputLevel=0;
    private static CallService instance;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService feedback=Executors.newSingleThreadExecutor();
    private final AudioStartupGate startup=new AudioStartupGate();
    private Future<?> feedbackTask;
    private String pendingAnswer;
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    private Runnable iceFallback;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private JavaAudioDeviceModule adm;
    private AudioSource source;
    private org.webrtc.AudioTrack localTrack,remoteTrack;
    private DataChannel channel;
    private String id,phase="idle",error="";
    private boolean silent,noMic,diagnostic,offerSent=false,readySent=false,audioConfigured=false;
    private volatile boolean ending;
    private boolean wakeChimePlayed=false;
    private volatile float peakInput=0,peakOutput=0;
    private volatile long playbackFrames=0,captureFrames=0;
    private volatile long lastSpoke=0;
    private long syntheticNext=0,syntheticSamples=0;
    private byte[] syntheticAudio=new byte[0];
    private long started,bytesIn=0,bytesOut=0,packetsIn=0,packetsOut=0,receivedAt=0;
    private int previousMode;
    private boolean previousSpeaker;
    private AudioManager audio;
    private PowerManager.WakeLock awake;
    private final Runnable poll=this::poll;
    private final AudioManager.OnAudioFocusChangeListener focus=change -> {if(change==AudioManager.AUDIOFOCUS_LOSS){startup.close();worker.execute(() -> finishCall("audio_focus_lost"));}};
    private String BRIDGE;
    private static final String DEVICE="http://127.0.0.1:8765";
    private volatile Milestones timing;
    private long serviceCreatedNs;
    private volatile boolean timingInputSeen,timingOutputSeen,timingNonzeroSeen;
    private void note(Milestones.Point point){Milestones t=timing;if(t!=null)t.mark(point);}

    @Override public void onCreate() {
        super.onCreate();serviceCreatedNs=SystemClock.elapsedRealtimeNanos();instance=this;audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel nc=new NotificationChannel("voice","贾维斯通话",NotificationManager.IMPORTANCE_LOW);nc.setSound(null,null);nc.enableVibration(false);nm.createNotificationChannel(nc);
    }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        long serviceStartNs=SystemClock.elapsedRealtimeNanos();
        if(i==null){stopSelf();return START_NOT_STICKY;}
        if("end".equals(i.getAction())){startup.close();worker.execute(() -> finishCall("requested"));return START_NOT_STICKY;}
        if(active||ending)return START_NOT_STICKY;
        startForeground(1,new Notification.Builder(this,"voice").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("贾维斯").setContentText("实时语音连接").setOngoing(true).build());
        active=true;id=i.getStringExtra("call_id");if(id==null)id=UUID.randomUUID().toString();
        BRIDGE=ConnectionSettings.load(this);
        timing=new Milestones(id,Milestones.deviceEnabled(),SystemClock::elapsedRealtimeNanos);
        timing.markAt(SERVICE_CREATE,serviceCreatedNs);timing.markAt(SERVICE_START,serviceStartNs);
        timing.markAt(i.getBooleanExtra("timing_new_intent",false)?ACTIVITY_NEW_INTENT:ACTIVITY_CREATE,i.getLongExtra("timing_activity_ns",0));
        timing.markAt(KWS_ACCEPTED,i.getLongExtra("timing_kws_accepted_ns",0));
        noMic=i.getBooleanExtra("no_mic",false);silent=i.getBooleanExtra("silent",false);diagnostic=i.getBooleanExtra("diagnostic",false);
        if(diagnostic && !noMic){active=false;stopSelf();return START_NOT_STICKY;}
        muted=false;inputLevel=0;outputLevel=0;started=SystemClock.elapsedRealtime();phase="connecting";label="正在连接";
        worker.execute(() -> {
            try {
            previousMode=audio.getMode();previousSpeaker=audio.isSpeakerphoneOn();
            if(!diagnostic||!silent){audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);audio.setMode(AudioManager.MODE_IN_COMMUNICATION);audio.setSpeakerphoneOn(true);audioConfigured=true;}
            feedbackTask=feedback.submit(() -> {
                try {
                    if(!silent&&!ending)wakeChimePlayed=WakeChime.play(timing);
                    worker.execute(() -> {if(!ending){startup.signal(AudioStartupGate.Signal.FEEDBACK_FINISHED);activateMedia();}});
                }catch(Exception failed){fail(failed);}
            });
            network.execute(this::prepare);
            }catch(Exception failed){fail(failed);}
        });
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    public static void toggleMute(){CallService s=instance;if(s!=null&&active){muted=!muted;if(s.localTrack!=null)s.localTrack.setEnabled(!muted);}}
    private JSONObject http(String base,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(path.endsWith("/offer")?30000:4000);
        connections.add(c);
        try {
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
            int status=c.getResponseCode();InputStream stream=status<300?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            if(stream!=null)try(InputStream in=stream){while((n=in.read(buffer))>0){out.write(buffer,0,n);if(out.size()>100000)throw new IOException("response_too_large");}}
            JSONObject r=new JSONObject(out.toString("UTF-8"));if(status>=300)throw new IOException(r.optString("detail",r.optString("error","http_"+status)));return r;
        } finally {connections.remove(c);c.disconnect();}
    }
    private void prepare() {
        try {
            if(ending||startup.isClosed())return;
            if(BRIDGE==null||BRIDGE.isEmpty())throw new IOException("请先在连接设置中填写桥接地址");
            note(STATUS_REQUEST);JSONObject status=http(BRIDGE,"/v1/realtime/status",null);note(STATUS_RESPONSE);
            if(ending||startup.isClosed())return;
            if(!diagnostic && status.optDouble("quiet_until",0)>System.currentTimeMillis()/1000.0){worker.execute(() -> finishCall("night_preparation"));return;}
            if(diagnostic&&noMic){File test=new File(getFilesDir(),"diagnostic-input.pcm");if(test.isFile()&&test.length()<=2000000){syntheticAudio=new byte[(int)test.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(test))){in.readFully(syntheticAudio);}}}
            if(ending||startup.isClosed())return;
            note(DEVICE_CLAIM_REQUEST);note(MIC_HANDOFF_WAIT_BEGIN);
            JSONObject owned=http(DEVICE,"/v1/voice/realtime",new JSONObject().put("active",true).put("id",id).put("phase",phase).put("metrics",snapshot()).put("await_release",true));
            note(DEVICE_CLAIM_RESPONSE);note(MIC_HANDOFF_WAIT_END);
            if(!id.equals(owned.optString("id"))||!owned.optBoolean("microphone_released"))throw new IOException("microphone_release_unconfirmed");
            note(MIC_RELEASE_CONFIRMED);receivedAt=SystemClock.elapsedRealtime();
            worker.execute(() -> {if(!ending&&!startup.isClosed()){startup.signal(AudioStartupGate.Signal.MICROPHONE_RELEASED);connectMedia();}});
        }catch(Exception e){if(!ending)fail(e);}
    }
    private void connectMedia() {
        try {
            if(ending||startup.isClosed())return;
            note(MEDIA_INIT_BEGIN);
            awake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"xiaodu:realtime");awake.acquire(3600000);
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
            note(GLOBAL_INIT_DONE);
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            JavaAudioDeviceModule.Builder audioBuilder=JavaAudioDeviceModule.builder(this).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                .setAudioSource(MediaRecorder.AudioSource.MIC).setAudioAttributes(attrs).setEnableVolumeLogger(false)
                .setAudioBufferCallback((buffer,format,channels,rate,bytes,timestamp) -> {
                    if(!noMic)return timestamp;
                    long now=System.nanoTime();if(syntheticNext==0||now-syntheticNext>100000000)syntheticNext=now;
                    long delay=syntheticNext-now;if(delay>0)java.util.concurrent.locks.LockSupport.parkNanos(delay);syntheticNext+=10000000;
                    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for(int frame=0;frame<buffer.capacity()/2/channels;frame++){long index=syntheticSamples++-rate*6;short v=index>=0&&index*2+1<syntheticAudio.length?(short)((syntheticAudio[(int)index*2]&255)|(syntheticAudio[(int)index*2+1]<<8)):0;for(int ch=0;ch<channels;ch++)buffer.putShort((frame*channels+ch)*2,v);}
                    return System.nanoTime();
                })
                .setSamplesReadyCallback(s -> {if(!timingInputSeen){timingInputSeen=true;note(noMic?SYNTHETIC_INPUT_FIRST_CALLBACK:MIC_FIRST_CALLBACK);}inputLevel=level(s.getData());peakInput=Math.max(peakInput,inputLevel);captureFrames++;})
                .setPlaybackSamplesReadyCallback(s -> {long callbackNs=timingNonzeroSeen||timing==null||!timing.enabled()?0:SystemClock.elapsedRealtimeNanos();if(!timingOutputSeen){timingOutputSeen=true;if(timing!=null)timing.markAt(FIRST_DOWNLINK_CALLBACK_PRE_PLAYBACK,callbackNs);}outputLevel=level(s.getData());peakOutput=Math.max(peakOutput,outputLevel);playbackFrames++;if(!timingNonzeroSeen&&outputLevel>0){timingNonzeroSeen=true;if(timing!=null)timing.markAt(FIRST_NONZERO_DOWNLINK_PCM_PRE_PLAYBACK,callbackNs);}})
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){public void onWebRtcAudioRecordInitError(String m){fail(new IOException("mic_init:"+m));}public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode c,String m){fail(new IOException("mic_start:"+m));}public void onWebRtcAudioRecordError(String m){fail(new IOException("mic:"+m));}})
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){public void onWebRtcAudioTrackInitError(String m){fail(new IOException("speaker_init:"+m));}public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode c,String m){fail(new IOException("speaker_start:"+m));}public void onWebRtcAudioTrackError(String m){fail(new IOException("speaker:"+m));}});
            audioBuilder.setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){public void onWebRtcAudioRecordStart(){note(AUDIO_RECORD_START);}public void onWebRtcAudioRecordStop(){}});
            audioBuilder.setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback(){public void onWebRtcAudioTrackStart(){note(AUDIO_PLAYOUT_START);}public void onWebRtcAudioTrackStop(){}});
            if(noMic)audioBuilder.setInputSampleRate(16000);
            adm=audioBuilder.createAudioDeviceModule();
            note(ADM_CREATED);
            adm.setSpeakerMute(silent);
            if(noMic)adm.setAudioRecordEnabled(false);
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
            note(FACTORY_CREATED);
            PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;config.tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.ENABLED;
            peer=factory.createPeerConnection(config,new PeerConnection.Observer(){
                public void onSignalingChange(PeerConnection.SignalingState s){}
                public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.FAILED)worker.execute(() -> finishCall("network_failed"));}
                public void onConnectionChange(PeerConnection.PeerConnectionState s){if(!active||ending)return;if(s==PeerConnection.PeerConnectionState.CONNECTED){note(PC_CONNECTED);worker.execute(() -> {if(ending)return;phase="connected";label="正在聆听";note(READY_QUEUED);network.execute(() -> {try{if(ending)return;note(READY_REQUEST);http(BRIDGE,"/v1/realtime/ready",new JSONObject().put("id",id));note(READY_RESPONSE);readySent=true;}catch(Exception e){if(!ending)fail(e);}});});}else if(s==PeerConnection.PeerConnectionState.FAILED)worker.execute(() -> finishCall("connection_failed"));}
                public void onIceConnectionReceivingChange(boolean r){}
                public void onIceGatheringChange(PeerConnection.IceGatheringState s){if(s==PeerConnection.IceGatheringState.COMPLETE){note(ICE_COMPLETE);worker.execute(() -> offer(OFFER_TRIGGER_ICE_COMPLETE));}}
                public void onIceCandidate(IceCandidate c){}
                public void onIceCandidatesRemoved(IceCandidate[] c){}
                public void onAddStream(org.webrtc.MediaStream s){}
                public void onRemoveStream(org.webrtc.MediaStream s){}
                public void onDataChannel(DataChannel c){observe(c);}
                public void onRenegotiationNeeded(){}
                public void onAddTrack(RtpReceiver receiver,org.webrtc.MediaStream[] streams){if(receiver.track() instanceof org.webrtc.AudioTrack){org.webrtc.AudioTrack track=(org.webrtc.AudioTrack)receiver.track();worker.execute(() -> {if(!ending){remoteTrack=track;remoteTrack.setEnabled(!silent&&startup.isOpen());}});}}
            });
            if(peer==null)throw new IOException("peer_unavailable");
            // These SDK controls stop actual audio-device recording/playout, not only RTP mute.
            peer.setAudioRecording(false);peer.setAudioPlayout(false);
            note(PEER_CREATED);
            {
                MediaConstraints constraints=new MediaConstraints();
                for(String key:new String[]{"googEchoCancellation","googNoiseSuppression","googAutoGainControl"})constraints.mandatory.add(new MediaConstraints.KeyValuePair(key,"true"));
                source=factory.createAudioSource(constraints);localTrack=factory.createAudioTrack("zhenweisi-mic",source);peer.addTrack(localTrack,Collections.singletonList("zhenweisi"));
            }
            observe(peer.createDataChannel("oai-events",new DataChannel.Init()));
            note(LOCAL_SDP_BEGIN);
            peer.createOffer(new Sdp(){public void onCreateSuccess(SessionDescription d){worker.execute(() -> {if(ending||peer==null)return;peer.setLocalDescription(new Sdp(){public void onSetSuccess(){note(LOCAL_SDP_SET);worker.execute(() -> {if(ending||offerSent)return;iceFallback=() -> {if(!ending){note(ICE_TIMEOUT_FIRED);worker.execute(() -> offer(OFFER_TRIGGER_TIMEOUT));}};main.postDelayed(iceFallback,1800);});}},d);});}},new MediaConstraints());
            startup.signal(AudioStartupGate.Signal.MEDIA_PREPARED);activateMedia();
            main.postDelayed(poll,1000);
        }catch(Exception e){fail(e);}
    }
    private void activateMedia(){
        if(ending||peer==null)return;
        try{
            if(startup.openOnce()){
                note(MEDIA_GATE_OPEN);peer.setAudioPlayout(!silent);peer.setAudioRecording(true);
                if(remoteTrack!=null)remoteTrack.setEnabled(!silent);
            }
            if(startup.isOpen()&&pendingAnswer!=null){
                String answer=pendingAnswer;pendingAnswer=null;
                peer.setRemoteDescription(new Sdp(){public void onSetSuccess(){note(REMOTE_SDP_SET);}},new SessionDescription(SessionDescription.Type.ANSWER,answer));
            }
        }catch(Exception failed){fail(failed);}
    }
    private static float level(byte[] data) {double energy=0;int n=data.length/2;for(int i=0;i<n;i++){int v=(short)((data[i*2]&255)|(data[i*2+1]<<8));energy+=(double)v*v;}return n==0?0:(float)Math.min(1,Math.sqrt(energy/n)/5500);}
    private void offer(Milestones.Point trigger) {
        if(!active||ending||startup.isClosed()||offerSent||peer==null||peer.getLocalDescription()==null)return;offerSent=true;
        if(iceFallback!=null)main.removeCallbacks(iceFallback);
        note(trigger);
        String sdp=peer.getLocalDescription().description;
        note(OFFER_QUEUED);
        network.execute(() -> {try{if(ending)return;note(OFFER_REQUEST);JSONObject result=http(BRIDGE,"/v1/realtime/offer",new JSONObject().put("id",id).put("sdp",sdp));note(ANSWER_RESPONSE);worker.execute(() -> {if(peer!=null&&!ending){pendingAnswer=result.optString("sdp");if(!startup.isOpen())note(REMOTE_SDP_DEFERRED);activateMedia();}});}catch(Exception e){if(!ending)fail(e);}});
    }
    private void observe(DataChannel c) {
        channel=c;c.registerObserver(new DataChannel.Observer(){public void onBufferedAmountChange(long n){}public void onStateChange(){try{if(c.state()==DataChannel.State.OPEN)note(DATA_CHANNEL_OPEN);}catch(RuntimeException ignored){}}public void onMessage(DataChannel.Buffer b){
            if(b.binary)return;ByteBuffer data=b.data;byte[] bytes=new byte[data.remaining()];data.get(bytes);
            try{JSONObject v=new JSONObject(new String(bytes,"UTF-8"));String type=v.optString("type");
                if("output_transcript.added".equals(type)){note(REMOTE_OUTPUT_TEXT_EVENT);lastSpoke=SystemClock.elapsedRealtime();}
                if("session.closed".equals(type))worker.execute(() -> finishCall("remote_closed"));
                if("error".equals(type)){JSONObject e=v.optJSONObject("error");if(e!=null)error=e.optString("message","voice_error");}
            }catch(Exception ignored){}
        }});
    }
    private void poll() {
        if(!active||ending)return;
        if(SystemClock.elapsedRealtime()-started>3500000){worker.execute(() -> finishCall("duration_limit"));return;}
        if(SystemClock.elapsedRealtime()-started>40000&&!"connected".equals(phase)){worker.execute(() -> finishCall("connection_timeout"));return;}
        if(peer!=null)peer.getStats(r -> {for(RTCStats s:r.getStatsMap().values()){Map<String,Object> m=s.getMembers();if("inbound-rtp".equals(s.getType())){bytesIn=number(m.get("bytesReceived"));packetsIn=number(m.get("packetsReceived"));if(packetsIn>0)note(FIRST_INBOUND_RTP_STATS_OBSERVED);}if("outbound-rtp".equals(s.getType())){bytesOut=number(m.get("bytesSent"));packetsOut=number(m.get("packetsSent"));}}});
        network.execute(() -> {try{JSONObject r=http(BRIDGE,"/v1/realtime/status?id="+id,null);if(!r.optString("id").equals(id)){worker.execute(() -> finishCall("session_lost"));return;}if(r.optString("id").equals(id)){
            String state=r.optString("state");if("ended".equals(state)||"failed".equals(state)){worker.execute(() -> finishCall(state));return;}
            if(r.optBoolean("backend_busy"))label="正在处理你的事情";else if("connected".equals(phase))label=muted?"麦克风已静音":(SystemClock.elapsedRealtime()-lastSpoke<700?"贾维斯正在说话":"正在聆听");
        }if(active)device(true);}catch(Exception e){error="connection_lost";if(SystemClock.elapsedRealtime()-receivedAt>12000)worker.execute(() -> finishCall("connection_lost"));}finally{if(active&&!ending)main.postDelayed(poll,1000);}});
    }
    private static long number(Object o){return o instanceof Number?((Number)o).longValue():0;}
    private JSONObject snapshot() throws Exception {return new JSONObject().put("id",id).put("active",active).put("phase",phase).put("no_mic",noMic).put("silent",silent).put("startup_parallel",true).put("media_gate_open",startup.isOpen()).put("software_aec_configured",true).put("wake_chime_played",wakeChimePlayed).put("wake_chime_ms",240).put("peak_input",peakInput).put("peak_output",peakOutput).put("playback_callbacks",playbackFrames).put("input_callbacks",captureFrames).put("bytes_received",bytesIn).put("bytes_sent",bytesOut).put("packets_received",packetsIn).put("packets_sent",packetsOut).put("input_level",inputLevel).put("output_level",outputLevel).put("error",error);}
    private void device(boolean on) throws Exception {http(DEVICE,"/v1/voice/realtime",new JSONObject().put("active",on).put("id",id).put("phase",phase).put("metrics",snapshot()));receivedAt=SystemClock.elapsedRealtime();}
    private void fail(Exception e){if(ending)return;startup.close();error=e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());worker.execute(() -> finishCall("failed"));}
    private void finishCall(String why) {
        if(ending)return;ending=true;startup.close();active=false;phase=why;inputLevel=0;outputLevel=0;main.removeCallbacks(poll);
        if(iceFallback!=null)main.removeCallbacks(iceFallback);
        for(HttpURLConnection connection:connections)connection.disconnect();
        if(feedbackTask!=null)try{feedbackTask.get(1500,TimeUnit.MILLISECONDS);}catch(Exception ignored){}
        feedback.shutdown();pendingAnswer=null;
        note(CLOSE_BEGIN);
        label="night_preparation".equals(why)?"语音暂时暂停，请稍后再试":("failed".equals(why)||why.contains("failed")||why.contains("timeout"))?"连接未成功，请稍后再试":"通话已结束";
        try{if(peer!=null){peer.close();peer.dispose();peer=null;}if(localTrack!=null){localTrack.dispose();localTrack=null;}if(source!=null){source.dispose();source=null;}if(factory!=null){factory.dispose();factory=null;}if(adm!=null){adm.release();adm=null;}}catch(Exception ignored){}
        if(audioConfigured){audio.abandonAudioFocus(focus);audio.setMode(previousMode);audio.setSpeakerphoneOn(previousSpeaker);}
        if(awake!=null&&awake.isHeld())awake.release();
        note(MEDIA_RELEASED);if(timing!=null)timing.finish(error.isEmpty()&&!why.contains("failed")&&!why.contains("timeout")?Milestones.Outcome.ENDED:Milestones.Outcome.FAILED);
        try{JSONObject summary=snapshot();if(timing!=null){JSONObject trace=new JSONObject(timing.snapshot());summary.put("timing",trace);timing.store(new File(getFilesDir(),"timings"),trace.toString().getBytes("UTF-8"));}try(FileOutputStream out=openFileOutput("last-call.json",MODE_PRIVATE)){out.write(summary.toString().getBytes("UTF-8"));}}catch(Exception ignored){}
        network.execute(() -> {try{if(id!=null)device(false);}catch(Exception ignored){}try{if(id!=null)http(BRIDGE,"/v1/realtime/end",new JSONObject().put("id",id));}catch(Exception ignored){}main.post(() -> {stopForeground(true);stopSelf();});});
    }
    @Override public void onDestroy(){if(active){startup.close();worker.execute(() -> finishCall("service_stopped"));}instance=null;super.onDestroy();}
    private class Sdp implements SdpObserver {public void onCreateSuccess(SessionDescription d){}public void onSetSuccess(){}public void onCreateFailure(String s){fail(new IOException(s));}public void onSetFailure(String s){fail(new IOException(s));}}
}
