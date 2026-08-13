# KoeType 混淆规则
# 如果开启 release 混淆，sherpa-onnx 的 JNI 与配置类不能混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
