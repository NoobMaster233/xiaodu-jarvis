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
    let factoryWanted=true;
    const SDK=Java.use('com.baidu.duer.dcs.framework.DcsSdkImpl');
    const startWake=SDK.startWakeup.overload();
    startWake.implementation=function(){if(factoryWanted)return startWake.call(this);};
    const Control=factory.registerClass({
        name:'local.xiaodu.speech.FactoryVoiceControl',
        implements:[factory.use('local.xiaodu.speech.VoiceRuntime$Control')],
        methods:{
            factoryWake:function(enabled){
                factoryWanted=!!enabled;
                if(enabled)startWake.call(sdk);else sdk.stopWakeup(null);
            }
        }
    });
    Voice.configure(Control.$new(),context);
    send({kind:'voice_hooks_loaded',local_wake:'真维斯'});
}
