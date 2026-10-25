"""Production native-wake state machine and callback adapter, without real audio."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class FactoryWakeTests(unittest.TestCase):
    def test_ownership_callbacks_cancellation_and_native_observer(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            f=folder/'com/baidu/duer/dcs/api/wakeup/BaseWakeup.java';f.parent.mkdir(parents=True)
            f.write_text('''package com.baidu.duer.dcs.api.wakeup;
public class BaseWakeup {public interface IWakeupListener {
 void onStartWakeupSuccess();void onInitWakeUpSucceed();void onWakeupSucceed();void onWakeupFailed();
}}''')
            test=folder/'FactoryWakeTest.java'
            test.write_text('''import local.xiaodu.speech.*;
import com.baidu.duer.dcs.api.wakeup.BaseWakeup.IWakeupListener;
import java.util.concurrent.atomic.AtomicInteger;
public class FactoryWakeTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static class Source {IWakeupListener listener;public void addWakeupListener(IWakeupListener l){listener=l;}}
 public static class Agent {Source source=new Source();public Source getWakeUp(){return source;}}
 public static class Sdk {Agent agent=new Agent();public Agent getWakeupAgent(){return agent;}}
 public static void main(String[] args)throws Exception {
  FactoryWakeLifecycle s=new FactoryWakeLifecycle();MicrophoneHandoff mic=new MicrophoneHandoff();
  check(mic.reserveKws());long init=s.requestStop();check(init!=0);
  check(!s.requestStart(false));check(s.requestStop()==0);check(!s.stopped(init+1));
  check(s.stopped(init));mic.kwsReleased();check(s.requestStart(false));check(mic.reserveKws());check(s.ready());
  // A live call blocks source restart and the media gate waits for a real SDK acknowledgement.
  mic.claim("call-a");long stop=s.requestStop();check(!s.ready());check(!s.requestStart(false));
  try{mic.awaitReleased("call-a",1);throw new AssertionError();}catch(java.io.IOException expected){}
  check(mic.kwsReserved());check(s.stopped(stop));mic.kwsReleased();mic.awaitReleased("call-a",100);
  check(!s.requestStart(mic.clientOwns()));mic.release("call-a");
  check(s.requestStart(mic.clientOwns()));check(mic.reserveKws());check(!s.stopped(stop));check(s.ready());
  // Cancellation during asynchronous stop cannot restart the source ahead of that stop ACK.
  mic.claim("call-b");stop=s.requestStop();mic.release("call-b");check(!s.requestStart(mic.clientOwns()));
  check(s.stopped(stop));mic.kwsReleased();check(s.requestStart(false));check(mic.reserveKws());check(s.ready());
  for(int i=0;i<100;i++){
   mic.claim("owner");stop=s.requestStop();check(s.requestStop()==0);check(!s.stopped(stop-1));
   check(s.stopped(stop));mic.kwsReleased();check(!s.requestStart(mic.clientOwns()));
   mic.release("owner");check(s.requestStart(false));check(mic.reserveKws());check(!s.stopped(stop));check(s.ready());
  }
  AtomicInteger ready=new AtomicInteger(),failed=new AtomicInteger();Sdk sdk=new Sdk();
  Object observer=FactoryWakeEvents.attach(sdk,ready::incrementAndGet,failed::incrementAndGet);
  check(observer!=null);sdk.agent.source.listener.onInitWakeUpSucceed();sdk.agent.source.listener.onWakeupSucceed();
  check(ready.get()==0&&failed.get()==0);
  sdk.agent.source.listener.onStartWakeupSuccess();check(ready.get()==1);
  sdk.agent.source.listener.onWakeupFailed();check(failed.get()==1);
  check(observer.equals(observer));check(!observer.equals(new Object()));
  System.out.println("native ownership, delayed ACK, cancellation, stale callback and observer checks passed");
 }
}''')
            prod=ROOT/'system/speech-http/src/local/xiaodu/speech'
            compile=subprocess.run(['javac','--release','8','-d',str(folder),str(f),str(test),*[str(prod/n) for n in ['FactoryWakeLifecycle.java','FactoryWakeEvents.java','MicrophoneHandoff.java']]],capture_output=True,text=True)
            self.assertEqual(compile.returncode,0,compile.stderr)
            run=subprocess.run(['java','-cp',str(folder),'FactoryWakeTest'],capture_output=True,text=True,timeout=8)
            self.assertEqual(run.returncode,0,run.stderr);self.assertIn('checks passed',run.stdout)


if __name__=='__main__':unittest.main()
