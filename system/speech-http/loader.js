'use strict';
Java.perform(function () {
    try {
        const context=Java.use('android.app.ActivityThread').currentApplication();
        const base='/data/data/com.baidu.duershow.dcs/files/xiaodu-http/';
        const loader=Java.use('dalvik.system.DexClassLoader').$new(base+'speech-http.jar',
            String(context.getCodeCacheDir().getAbsolutePath()),base+'lib',context.getClassLoader());
        const factory=Java.ClassFactory.get(loader);
        const result=factory.use('local.xiaodu.speech.HttpSpeechServer').start(context);
        send({kind:'speech_http_loaded',result:String(result),pid:Process.id});
        const Voice=factory.use('local.xiaodu.speech.VoiceRuntime');
        let sdk=null;
        function selectSdk(){Java.perform(function(){Java.choose('com.baidu.duer.dcs.framework.DcsSdkImpl',{
            onMatch(instance){
                const current=Java.use('com.baidu.duer.dcs.util.StaticUtil').voiceRequest.value;
                if(current!==null && instance.voiceRequest.value!==null && instance.voiceRequest.value.equals(current)) {
                    const agent=instance.getWakeupAgent();
                    if(agent===null||agent.getWakeUp()===null||!agent.isInitSuccess())return;
                    sdk=Java.retain(instance);return 'stop';
                }
            },
            onComplete(){
                if(sdk===null){setTimeout(selectSdk,2000);return;}
                installVoice(sdk,Voice,factory,context);
            }
        });});}
        selectSdk();
    } catch (error) {
        // Never include configuration, credentials, or request text in bootstrap output.
        send({kind:'speech_http_load_failed',error_class:String(error.$className||error.name||'Error')});
    }
});

function installVoice(sdk,Voice,factory,context) {
    const source=String(Java.use('android.os.SystemProperties').get('persist.xiaodu.wake_source','factory'));
    const nativeMode=source!=='local';
    let factoryWanted=!nativeMode;
    const SDK=Java.use('com.baidu.duer.dcs.framework.DcsSdkImpl');
    const startWake=SDK.startWakeup.overload();
    startWake.implementation=function(){if(factoryWanted)return startWake.call(this);};
    const Stop=factory.registerClass({
        name:'local.xiaodu.speech.FactoryWakeStop',
        implements:[Java.use('com.baidu.duer.dcs.api.wakeup.IStopWakeupListener')],
        fields:{callback:'java.lang.Runnable'},
        methods:{onStopWakeup:function(){const done=this.callback.value;this.callback.value=null;if(done!==null)done.run();}}
    });
    const Control=factory.registerClass({
        name:'local.xiaodu.speech.FactoryVoiceControl',
        implements:[factory.use('local.xiaodu.speech.VoiceRuntime$Control')],
        methods:{
            factoryWake:function(enabled,stopped){
                factoryWanted=!!enabled;
                if(enabled)startWake.call(sdk);
                else if(stopped!==null){const listener=Stop.$new();listener.callback.value=stopped;sdk.stopWakeup(listener);}
                else sdk.stopWakeup(null);
            }
        }
    });
    if(nativeMode){
        const wake=Java.use('com.baidu.duer.dcs.link.puffer.wakeup.WakeUpEngine');
        const params=Java.use('com.baidu.duer.dcs.link.puffer.wakeup.OneshotParam');
        // Intercept before the agent's warning sound, factory ASR and business/UI.
        wake.fireOnWakeupSuccess.overload('org.json.JSONObject').implementation=function(statistic){
            if(String(params.wpWords.value)==='小度小度')Voice.factoryWakeDetected();
        };
        Voice.configureFactory(Control.$new(),context,sdk);
    }else Voice.configure(Control.$new(),context);
    send({kind:'voice_hooks_loaded',wake_source:nativeMode?'factory':'local',wake_word:nativeMode?'小度小度':'真维斯'});
}
