# Reglas de R8 que viajan con la librería: las recibe toda app que la use, sin copiarlas.
# kotlinx.serialization: los serializadores de las clases @Serializable se buscan por nombre.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.dotrino.sdk.**$$serializer { *; }
-keepclassmembers class com.dotrino.sdk.** { *** Companion; }
-keepclasseswithmembers class com.dotrino.sdk.** { kotlinx.serialization.KSerializer serializer(...); }
