package local.xiaodu.speech;

import java.lang.reflect.*;

/** Observe native readiness/errors only; never reads audio or speech payloads. */
public final class FactoryWakeEvents {
    private FactoryWakeEvents(){}
    public static Object attach(Object sdk,Runnable ready,Runnable failed) throws Exception {
        Object agent=sdk.getClass().getMethod("getWakeupAgent").invoke(sdk);
        Object source=agent.getClass().getMethod("getWakeUp").invoke(agent);
        ClassLoader loader=source.getClass().getClassLoader();
        Class<?> type=Class.forName("com.baidu.duer.dcs.api.wakeup.BaseWakeup$IWakeupListener",true,loader);
        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->{
            String name=method.getName();
            if("onStartWakeupSuccess".equals(name))ready.run();
            else if("onWakeupFailed".equals(name)||"onInitWakeUpFailed".equals(name))failed.run();
            else if("hashCode".equals(name))return System.identityHashCode(proxy);
            else if("equals".equals(name))return proxy==args[0];
            else if("toString".equals(name))return "xiaodu-factory-wake-observer";
            return null;
        });
        source.getClass().getMethod("addWakeupListener",type).invoke(source,listener);
        return listener;
    }
}
