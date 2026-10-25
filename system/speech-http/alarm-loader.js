'use strict';
Java.perform(function () {
    try {
        const context=Java.use('android.app.ActivityThread').currentApplication();
        const base='/data/data/com.baidu.launcher/files/xiaodu-control/';
        const loader=Java.use('dalvik.system.DexClassLoader').$new(base+'speech-http.jar',
            String(context.getCodeCacheDir().getAbsolutePath()),null,context.getClassLoader());
        const factory=Java.ClassFactory.get(loader);
        const result=factory.use('local.xiaodu.speech.FactoryAlarmControl').start(context);
        const deviceResult=factory.use('local.xiaodu.speech.FactoryDeviceControl').start(context);
        send({kind:'device_control_loaded',result:String(deviceResult)});
        send({kind:'alarm_control_loaded',result:String(result)});
    } catch(error) {
        send({kind:'alarm_control_load_failed',error_class:String(error.$className||error.name||'Error')});
    }
});
