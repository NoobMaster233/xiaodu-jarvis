package local.xiaodu.timing;

import java.util.*;
import java.util.function.LongSupplier;
import java.io.*;

/** Bounded, first-occurrence timestamps only. No media, text, exceptions or SDP. */
public final class Milestones {
    public enum Point {
        KWS_ACCEPTED, KWS_STOP_BEGIN, KWS_STOP_DONE, KWS_STOP_FAILED, KWS_RELEASE_DONE,
        FACTORY_WAKE_STOP_BEGIN, FACTORY_WAKE_STOP_ACK,
        ACTIVITY_CREATE, ACTIVITY_NEW_INTENT, SERVICE_CREATE, SERVICE_START,
        CHIME_BEGIN, CHIME_PLAY, CHIME_END, CHIME_RELEASED, CHIME_NOT_COMPLETED,
        STATUS_REQUEST, STATUS_RESPONSE, DEVICE_CLAIM_REQUEST, DEVICE_CLAIM_RESPONSE,
        MIC_HANDOFF_WAIT_BEGIN, MIC_HANDOFF_WAIT_END, MIC_RELEASE_CONFIRMED, MEDIA_GATE_OPEN,
        MEDIA_INIT_BEGIN, GLOBAL_INIT_DONE, ADM_CREATED, FACTORY_CREATED, PEER_CREATED,
        LOCAL_SDP_BEGIN, LOCAL_SDP_SET, ICE_COMPLETE, ICE_TIMEOUT_FIRED,
        OFFER_TRIGGER_ICE_COMPLETE, OFFER_TRIGGER_TIMEOUT, OFFER_QUEUED, OFFER_REQUEST, ANSWER_RESPONSE,
        REMOTE_SDP_SET, REMOTE_SDP_DEFERRED, PC_CONNECTED, DATA_CHANNEL_OPEN, READY_QUEUED, READY_REQUEST, READY_RESPONSE,
        AUDIO_RECORD_START, AUDIO_PLAYOUT_START, FIRST_INBOUND_RTP_STATS_OBSERVED, REMOTE_OUTPUT_TEXT_EVENT,
        MIC_FIRST_CALLBACK, SYNTHETIC_INPUT_FIRST_CALLBACK,
        FIRST_DOWNLINK_CALLBACK_PRE_PLAYBACK, FIRST_NONZERO_DOWNLINK_PCM_PRE_PLAYBACK,
        CLOSE_BEGIN, MEDIA_RELEASED, CALL_END
    }
    public enum Outcome { ENDED, FAILED }
    private final String callId;
    private final boolean enabled;
    private final LongSupplier clock;
    private final EnumMap<Point,Long> marks=new EnumMap<>(Point.class);
    private boolean sealed;
    private Outcome outcome;
    private boolean stored;

    public Milestones(String callId,boolean enabled,LongSupplier clock) {
        UUID.fromString(callId);this.callId=callId;this.enabled=enabled;this.clock=clock;
    }
    public boolean enabled(){return enabled;}
    public synchronized void mark(Point point){if(enabled&&!sealed&&!marks.containsKey(point))marks.put(point,clock.getAsLong());}
    public synchronized void markAt(Point point,long ns){if(enabled&&!sealed&&ns>0&&!marks.containsKey(point))marks.put(point,ns);}
    public synchronized long get(Point point){Long value=marks.get(point);return value==null?0:value;}
    public synchronized boolean finish(Outcome value){if(!enabled||sealed)return false;mark(Point.CALL_END);outcome=value;sealed=true;return true;}
    public synchronized Map<String,Object> snapshot(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",enabled);result.put("call_id",callId);
        result.put("clock","android_elapsed_realtime_ns");
        if(!enabled)return result;
        Map<String,Long> values=new LinkedHashMap<>();for(Map.Entry<Point,Long> e:marks.entrySet())values.put(e.getKey().name().toLowerCase(Locale.ROOT),e.getValue());
        result.put("milestones_ns",values);result.put("sealed",sealed);
        result.put("first_nonzero_downlink_pcm_semantics","playback_callback_before_render_not_speaker_audibility");
        if(outcome!=null)result.put("outcome",outcome.name().toLowerCase(Locale.ROOT));
        Map<String,Double> durations=new LinkedHashMap<>();
        duration(durations,"wake_to_service",Point.KWS_ACCEPTED,Point.SERVICE_START);
        duration(durations,"chime",Point.CHIME_BEGIN,Point.CHIME_END);
        duration(durations,"kws_stop_release",Point.KWS_STOP_BEGIN,Point.KWS_RELEASE_DONE);
        duration(durations,"factory_wake_stop_ack",Point.FACTORY_WAKE_STOP_BEGIN,Point.FACTORY_WAKE_STOP_ACK);
        duration(durations,"handoff_wait",Point.MIC_HANDOFF_WAIT_BEGIN,Point.MIC_HANDOFF_WAIT_END);
        duration(durations,"media_init",Point.MEDIA_INIT_BEGIN,Point.PEER_CREATED);
        duration(durations,"local_sdp_to_offer",Point.LOCAL_SDP_SET,Point.OFFER_REQUEST);
        duration(durations,"offer_roundtrip",Point.OFFER_REQUEST,Point.ANSWER_RESPONSE);
        duration(durations,"ready_roundtrip",Point.READY_REQUEST,Point.READY_RESPONSE);
        result.put("durations_ms",durations);return result;
    }
    private void duration(Map<String,Double> out,String name,Point begin,Point end){Long a=marks.get(begin),b=marks.get(end);if(a!=null&&b!=null&&b>=a)out.put(name,(b-a)/1000000.0);}
    /** One private, bounded history file at call end, never on media callbacks. */
    public synchronized boolean store(File directory,byte[] json){
        if(!enabled||!sealed||stored||json.length>16384)return false;
        File temporary=new File(directory,callId+".tmp");
        try {
            if(!directory.isDirectory()&&!directory.mkdirs())return false;
            directory.setReadable(false,false);directory.setWritable(false,false);directory.setExecutable(false,false);
            directory.setReadable(true,true);directory.setWritable(true,true);directory.setExecutable(true,true);
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(json);}
            temporary.setReadable(false,false);temporary.setWritable(false,false);temporary.setReadable(true,true);temporary.setWritable(true,true);
            if(!temporary.renameTo(new File(directory,callId+".json")))return false;
            stored=true;
            File[] files=directory.listFiles(f -> f.getName().matches("[0-9a-fA-F-]{36}\\.json"));
            if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-20;i++)files[i].delete();}
            return true;
        }catch(IOException|SecurityException ignored){return false;}
        finally{try{if(temporary.isFile())temporary.delete();}catch(SecurityException ignored){}}
    }
    /** Sampled once per call; disabling never affects the audio/control path. */
    public static boolean deviceEnabled(){
        try{return (Boolean)Class.forName("android.os.SystemProperties").getMethod("getBoolean",String.class,boolean.class).invoke(null,"persist.xiaodu.voice_timing",true);}
        catch(Exception ignored){return true;}
    }
}
