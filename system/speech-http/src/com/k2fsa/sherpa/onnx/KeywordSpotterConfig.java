// JNI ABI adapted from sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/jni/.
package com.k2fsa.sherpa.onnx;
public final class KeywordSpotterConfig {
public FeatureConfig featConfig=new FeatureConfig();public OnlineModelConfig modelConfig=new OnlineModelConfig();
public int maxActivePaths=4,numTrailingBlanks=2;public String keywordsFile="";
public float keywordsScore=1f,keywordsThreshold=.25f;
}
