// JNI ABI adapted from sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/jni/.
package com.k2fsa.sherpa.onnx;
public final class OnlineModelConfig {
public OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
public OnlineParaformerModelConfig paraformer=new OnlineParaformerModelConfig();
public OnlineZipformer2CtcModelConfig zipformer2Ctc=new OnlineZipformer2CtcModelConfig();
public OnlineNeMoCtcModelConfig neMoCtc=new OnlineNeMoCtcModelConfig();
public OnlineToneCtcModelConfig toneCtc=new OnlineToneCtcModelConfig();
public String tokens="",provider="cpu",modelType="zipformer2",modelingUnit="",bpeVocab="";
public int numThreads=1; public boolean debug=false;
}
