import local.xiaodu.timing.Milestones;
import static local.xiaodu.timing.Milestones.Point.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.io.File;

/** Host JVM test of the actual shared production timestamp collector. */
public final class VoiceMilestonesTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        String id="12345678-1234-1234-1234-123456789abc";
        AtomicLong now=new AtomicLong(10000000);
        Milestones t=new Milestones(id,true,now::get);
        t.markAt(KWS_ACCEPTED,7000000);t.mark(SERVICE_START);now.set(20000000);t.mark(SERVICE_START);
        check(t.get(SERVICE_START)==10000000);
        Map<String,Object> snap=t.snapshot();
        check(((Map<?,?>)snap.get("durations_ms")).get("wake_to_service").equals(3.0));
        check(snap.get("first_nonzero_downlink_pcm_semantics").equals("playback_callback_before_render_not_speaker_audibility"));
        ((Map<?,?>)snap.get("milestones_ns")).clear();check(t.get(KWS_ACCEPTED)==7000000);
        check(t.finish(Milestones.Outcome.ENDED));check(!t.finish(Milestones.Outcome.FAILED));
        t.mark(READY_RESPONSE);check(t.get(READY_RESPONSE)==0);
        check(t.snapshot().get("outcome").equals("ended"));
        Milestones disabled=new Milestones(id,false,() -> {throw new AssertionError("disabled clock used");});
        disabled.mark(SERVICE_START);disabled.markAt(KWS_ACCEPTED,1);check(!disabled.finish(Milestones.Outcome.ENDED));
        check(!disabled.snapshot().containsKey("milestones_ns"));
        Milestones parallel=new Milestones(id,true,now::incrementAndGet);
        Thread a=new Thread(() -> {for(int i=0;i<100;i++)parallel.mark(FIRST_NONZERO_DOWNLINK_PCM_PRE_PLAYBACK);});
        Thread b=new Thread(() -> {for(int i=0;i<100;i++)parallel.mark(FIRST_NONZERO_DOWNLINK_PCM_PRE_PLAYBACK);});
        a.start();b.start();a.join();b.join();check(((Map<?,?>)parallel.snapshot().get("milestones_ns")).size()==1);
        Milestones next=new Milestones("12345678-1234-1234-1234-123456789abd",true,now::get);check(next.get(SERVICE_START)==0);
        for(Milestones.Point point:Milestones.Point.values())next.mark(point);
        check(((Map<?,?>)next.snapshot().get("milestones_ns")).size()==Milestones.Point.values().length);
        check(next.snapshot().keySet().equals(new LinkedHashSet<>(Arrays.asList("enabled","call_id","clock","milestones_ns","sealed","first_nonzero_downlink_pcm_semantics","durations_ms"))));
        File directory=new File(args[0],"history");
        check(!disabled.store(directory,new byte[0]));check(!directory.exists());
        for(int i=0;i<24;i++){
            Milestones item=new Milestones(String.format("12345678-1234-1234-1234-%012d",i),true,now::get);
            item.mark(SERVICE_START);item.finish(Milestones.Outcome.ENDED);
            check(item.store(directory,"{}".getBytes("UTF-8")));check(!item.store(directory,"{}".getBytes("UTF-8")));
        }
        check(directory.listFiles(f -> f.getName().endsWith(".json")).length==20);
        System.out.println("PASS: first-only, monotonic durations, proxy label, snapshot isolation, sealing, disabled mode, concurrent callbacks, per-call isolation, bounded fields");
    }
}
