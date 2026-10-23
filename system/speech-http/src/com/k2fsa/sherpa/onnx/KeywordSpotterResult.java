// JNI ABI adapted from sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/jni/.
package com.k2fsa.sherpa.onnx;
public final class KeywordSpotterResult {
public final String keyword;public final String[] tokens;public final float[] timestamps;
public KeywordSpotterResult(String keyword,String[] tokens,float[] timestamps){this.keyword=keyword;this.tokens=tokens;this.timestamps=timestamps;}
}
