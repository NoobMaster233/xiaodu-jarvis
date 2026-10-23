// JNI ABI adapted from sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/jni/.
package com.k2fsa.sherpa.onnx;
public final class KeywordSpotter {
static {System.loadLibrary("sherpa-onnx-jni");}
public native long newFromFile(KeywordSpotterConfig config);
public native void delete(long p);
public native long createStream(long p,String keywords);
public native boolean isReady(long p,long stream);
public native void decode(long p,long stream);
public native void reset(long p,long stream);
public native KeywordSpotterResult getResult(long p,long stream);
}
