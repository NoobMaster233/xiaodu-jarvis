package local.xiaodu.speech;

/** Native wake engine ownership. A stop request never implies a stop acknowledgement. */
public final class FactoryWakeLifecycle {
    public enum Phase { UNKNOWN, STARTING, READY, STOPPING, STOPPED }
    private Phase phase=Phase.UNKNOWN;
    private long generation;
    public synchronized long requestStop() {
        if(phase==Phase.STOPPING||phase==Phase.STOPPED)return 0;
        phase=Phase.STOPPING;return ++generation;
    }
    public synchronized boolean stopped(long token) {
        if(token!=generation||phase!=Phase.STOPPING)return false;
        phase=Phase.STOPPED;return true;
    }
    public synchronized boolean requestStart(boolean clientOwns) {
        if(clientOwns||phase!=Phase.STOPPED)return false;
        generation++;phase=Phase.STARTING;return true;
    }
    public synchronized boolean ready() {
        if(phase!=Phase.STARTING)return false;
        phase=Phase.READY;return true;
    }
    public synchronized Phase phase(){return phase;}
    public synchronized long generation(){return generation;}
}
