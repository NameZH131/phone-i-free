# Keep rules for release builds with R8 enabled

# Keep annotations and inner classes (Room / Compose dependencies)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Always keep native methods bound via JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# sherpa-onnx (KeywordSpotter etc. loaded via JNI/reflection)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# MediaPipe Tasks (GestureRecognizer loaded via JNI)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.mediapipe.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Guava Flogger — MediaPipe uses stack-walking caller detection that breaks under R8 inlining
-keep class com.google.common.flogger.** { *; }
