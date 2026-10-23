import local.xiaodu.speech.MicrophoneHandoff;
import local.xiaodu.voice.AudioStartupGate;
import local.xiaodu.voice.AudioStartupGate.Signal;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real production coordination classes; no Android/mic/network. */
public final class StartupCoordinationTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    interface Throwing {void run() throws Exception;}
    static void fails(Throwing action) throws Exception {try{action.run();throw new AssertionError("expected failure");}catch(IOException expected){}}
    public static void main(String[] args) throws Exception {
        for(Signal a:Signal.values())for(Signal b:Signal.values())for(Signal c:Signal.values()){
            if(a==b||a==c||b==c)continue;
            AudioStartupGate g=new AudioStartupGate();g.signal(a);check(!g.openOnce());
            g.signal(b);check(!g.openOnce());g.signal(c);check(g.openOnce());check(!g.openOnce());
            g.close();check(!g.isOpen());g.signal(a);check(!g.openOnce());
        }
        for(Signal signal:Signal.values()){
            AudioStartupGate g=new AudioStartupGate();g.signal(signal);g.close();
            for(Signal late:Signal.values())g.signal(late);check(!g.openOnce());
        }
        AudioStartupGate g=new AudioStartupGate();AtomicInteger opens=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            Future<?>[] signals=new Future<?>[3];int n=0;
            for(Signal signal:Signal.values())signals[n++]=pool.submit(() -> {g.signal(signal);if(g.openOnce())opens.incrementAndGet();});
            for(Future<?> f:signals)f.get(1,TimeUnit.SECONDS);check(opens.get()==1);

            MicrophoneHandoff mic=new MicrophoneHandoff();check(mic.reserveKws());
            mic.claim("call-a");check(mic.clientOwns());check(!mic.reserveKws());
            CountDownLatch waiting=new CountDownLatch(1);
            Future<?> ack=pool.submit(() -> {waiting.countDown();try{mic.awaitReleased("call-a",1000);}catch(Exception e){throw new RuntimeException(e);}});
            waiting.await();check(mic.kwsReserved());check(!ack.isDone());
            mic.release("stale-call");check(mic.clientOwns());
            mic.kwsReleased();ack.get(1,TimeUnit.SECONDS);
            fails(() -> mic.claim("call-b"));fails(() -> mic.awaitReleased("wrong-call",1000));
            Future<?> kws=pool.submit(() -> {try{mic.awaitKwsPermission();}catch(Exception e){throw new RuntimeException(e);}});
            mic.release("call-a");kws.get(1,TimeUnit.SECONDS);check(mic.reserveKws());
            mic.claim("call-b");fails(() -> mic.awaitReleased("call-b",1));check(mic.kwsReserved());
            mic.release("call-b");mic.claim("call-c");fails(() -> mic.awaitReleased("call-b",1));
            mic.kwsReleased();mic.awaitReleased("call-c",1000);mic.release("call-c");
            // Both possible orders of reservation/claim have a safe, explicit owner.
            for(int i=0;i<100;i++){
                MicrophoneHandoff race=new MicrophoneHandoff();CountDownLatch go=new CountDownLatch(1);
                Future<Boolean> reserve=pool.submit(() -> {go.await();return race.reserveKws();});
                Future<?> claim=pool.submit(() -> {try{go.await();race.claim("owner");}catch(Exception e){throw new RuntimeException(e);}});
                go.countDown();boolean didReserve=reserve.get();claim.get();check(race.clientOwns());check(!race.reserveKws());
                if(didReserve){check(race.kwsReserved());race.kwsReleased();}
                race.awaitReleased("owner",1000);
            }
        }finally{pool.shutdownNow();}
        System.out.println("PASS: all startup event orders, cancellation, open-once, real release ACK, stale owner, timeout, capture/claim races");
    }
}
