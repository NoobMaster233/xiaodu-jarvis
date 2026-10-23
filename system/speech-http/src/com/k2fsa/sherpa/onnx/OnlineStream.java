// JNI ABI adapted from sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/jni/.
package com.k2fsa.sherpa.onnx;
public final class OnlineStream {
public long ptr;public OnlineStream(long p){ptr=p;}
public native void acceptWaveform(long p,float[] samples,int sampleRate);
public native void delete(long p);
}
