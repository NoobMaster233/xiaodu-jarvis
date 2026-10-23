package local.xiaodu.speech;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Actual recorder ownership, independent of diagnostics and wall-clock time. */
public final class MicrophoneHandoff {
    private String client;
    private boolean reserved;

    public synchronized boolean reserveKws(){
        if(client!=null)return false;
        if(reserved)throw new IllegalStateException("kws_already_reserved");
        reserved=true;return true;
    }
    /** Called only after AudioRecord.release() has returned (or creation failed). */
    public synchronized void kwsReleased(){reserved=false;notifyAll();}
    public synchronized void claim(String id) throws IOException {
        if(client!=null&&!client.equals(id))throw new IOException("microphone_owned_by_other_call");
        client=id;notifyAll();
    }
    public synchronized void release(String id){
        if(client!=null&&client.equals(id)){client=null;notifyAll();}
    }
    public synchronized boolean clientOwns(){return client!=null;}
    public synchronized boolean kwsReserved(){return reserved;}
    public synchronized void awaitKwsPermission() throws InterruptedException {
        while(client!=null)wait();
    }
    public synchronized void awaitReleased(String id,long timeoutMs) throws IOException,InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while(true){
            if(!id.equals(client))throw new IOException("microphone_call_changed");
            if(!reserved)return;
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new IOException("microphone_release_timeout");
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
        }
    }
}
