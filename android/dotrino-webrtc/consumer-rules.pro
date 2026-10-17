# Reglas de R8 que viajan con la librería. libwebrtc llama a Java desde C++ por nombre
# (@CalledByNative, y su cargador org.jni_zero), y su AAR no trae reglas: si R8 renombra o
# quita esas clases, la app muere al arrancar (ClassNotFoundException: org.jni_zero.JniZero).
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
# JniZeroJni no viene en el AAR (lo resuelve el lado nativo); sin esto R8 se para por la referencia.
-dontwarn org.jni_zero.JniZeroJni
