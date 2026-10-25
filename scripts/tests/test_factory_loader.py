"""Run the actual bootstrap JS with a small native SDK double (no device/audio)."""
from pathlib import Path
import subprocess
import unittest

ROOT=Path(__file__).resolve().parents[2]


class FactoryLoaderTest(unittest.TestCase):
    def test_sdk_readiness_keyword_routing_stop_ack_and_local_fallback(self):
        program=r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(process.argv[1],'utf8');
function check(mode,readyInitially){
 const calls={start:0,stop:0,detected:0},timeouts=[],events=[];
 let initialized=readyInitially,selected=null,listener=null;
 const request={equals(other){return other===this;}};
 const agent={getWakeUp(){return {};},isInitSuccess(){return initialized;}};
 const sdk={voiceRequest:{value:request},getWakeupAgent(){return agent;},stopWakeup(l){calls.stop++;listener=l;}};
 const start={call(){calls.start++;},implementation:null};
 const fire={implementation:null};const params={wpWords:{value:'小度小度'}};
 const voice={configure(c){selected=c;calls.mode='local';},configureFactory(c){selected=c;calls.mode='factory';},factoryWakeDetected(){calls.detected++;}};
 const context={getCodeCacheDir(){return {getAbsolutePath(){return '/code-cache';}};},getClassLoader(){return {};}};
 const factory={use(name){if(name.endsWith('HttpSpeechServer'))return {start(){return 'started';}};if(name.endsWith('VoiceRuntime'))return voice;return {};},
  registerClass(spec){return {$new(){let value={};for(const field in spec.fields||{})value[field]={value:null};for(const method in spec.methods)value[method]=spec.methods[method].bind(value);return value;}};}};
 const java={perform(f){f();},retain(v){return v;},ClassFactory:{get(){return factory;}},choose(name,callbacks){callbacks.onMatch(sdk);callbacks.onComplete();},
  use(name){
   if(name==='android.app.ActivityThread')return {currentApplication(){return context;}};
   if(name==='dalvik.system.DexClassLoader')return {$new(){return {};}};
   if(name==='com.baidu.duer.dcs.util.StaticUtil')return {voiceRequest:{value:request}};
   if(name==='android.os.SystemProperties')return {get(){return mode;}};
   if(name==='com.baidu.duer.dcs.framework.DcsSdkImpl')return {startWakeup:{overload(){return start;}}};
   if(name.endsWith('WakeUpEngine'))return {fireOnWakeupSuccess:{overload(){return fire;}}};
   if(name.endsWith('OneshotParam'))return params;
   return {};
  }};
 const sandbox={Java:java,Process:{id:1},send(v){events.push(v);},setTimeout(f){timeouts.push(f);}};
 vm.runInNewContext(source,sandbox);
 if(!readyInitially){assert.strictEqual(selected,null);assert.strictEqual(timeouts.length,1);initialized=true;timeouts.shift()();}
 assert(selected,'initialized SDK must be selected using its isInitSuccess() getter');
 assert.strictEqual(calls.mode,mode==='local'?'local':'factory');
 if(mode!=='local'){
  start.implementation.call(sdk);assert.strictEqual(calls.start,0);
  selected.factoryWake(true,null);assert.strictEqual(calls.start,1);
  start.implementation.call(sdk);assert.strictEqual(calls.start,2);
  let acknowledged=0;selected.factoryWake(false,{run(){acknowledged++;}});
  start.implementation.call(sdk);assert.strictEqual(calls.start,2);assert.strictEqual(acknowledged,0);
  listener.onStopWakeup();listener.onStopWakeup();assert.strictEqual(acknowledged,1);
  params.wpWords.value='小度';fire.implementation(null);assert.strictEqual(calls.detected,0);
  params.wpWords.value='真维斯';fire.implementation(null);assert.strictEqual(calls.detected,0);
  params.wpWords.value='小度小度';fire.implementation(null);assert.strictEqual(calls.detected,1);
 }else assert.strictEqual(fire.implementation,null);
 assert(events.some(e=>e.kind==='voice_hooks_loaded'));
}
check('factory',true);check('factory',false);check('local',true);
console.log('readiness getter, native suppression/routing, stop ACK once and local fallback passed');
'''
        result=subprocess.run(['node','-e',program,str(ROOT/'system/speech-http/loader.js')],capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('passed',result.stdout)


if __name__=='__main__':unittest.main()
