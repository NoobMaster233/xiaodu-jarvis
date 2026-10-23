package local.xiaodu.voice;

import java.util.EnumSet;

/** One-call event barrier: elapsed time never grants microphone/playout access. */
public final class AudioStartupGate {
    public enum Signal { MICROPHONE_RELEASED, FEEDBACK_FINISHED, MEDIA_PREPARED }
    private final EnumSet<Signal> seen=EnumSet.noneOf(Signal.class);
    private boolean opened,closed;
    public synchronized void signal(Signal value){if(!closed)seen.add(value);}
    public synchronized boolean openOnce(){
        if(closed||opened||seen.size()!=Signal.values().length)return false;
        opened=true;return true;
    }
    public synchronized boolean isOpen(){return opened&&!closed;}
    public synchronized boolean isClosed(){return closed;}
    public synchronized void close(){closed=true;}
}
